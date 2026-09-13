#!/usr/bin/env python3
"""
rumi.py — Rumi voice-assistant bridge for the Rumi Controller Android app.

Sends commands to the Android app (`com.rumi.controller`) as broadcast
intents. Runs on-device under Termux (`am broadcast`) or from a host
machine over ADB (`adb shell am broadcast`).

PROTOCOL — single source of truth, must stay byte-identical with
  android/app/src/main/java/com/rumi/controller/PlayProtocol.kt

  action  com.rumi.voiceassistant.PLAY_SPOTIFY     extra  query=<str>
  action  com.rumi.voiceassistant.MEDIA_CONTROL    extra  command=toggle|play|pause|next|prev
  action  com.rumi.voiceassistant.VOLUME_CONTROL   extra  command=up|down|mute|auto_lower
  package com.rumi.controller

USAGE
  rumi.py play <song...>          Spotify search + autoplay (ONE broadcast)
  rumi.py play | pause | toggle   hardware media keys (play keyboard/headset)
  rumi.py next | prev             skip tracks
  rumi.py volume up|down|mute     media stream volume
  rumi.py duck                    drop volume to ~10%% while Rumi speaks
  rumi.py raw <action> <k> <v>    escape hatch, any broadcast
  rumi.py --loop                  daemon mode: read commands from stdin,
                                  zero per-command interpreter startup
  rumi.py --doctor                verify transport + app reachability

SPEED NOTES
  * Only `os`/`sys` are imported at module load; subprocess/shutil are
    imported lazily on first use (matters a lot on phone CPUs).
  * No argparse — parsing is a tiny hand-rolled scanner.
  * Spotify playback is a single `am broadcast` (the receiver launches
    Spotify itself), not two shell invocations.
  * `--loop` keeps one Python process alive so repeated commands cost
    ~1 ms of Python + one `am` exec each.

Exit codes: 0 ok · 1 transport/send failure · 2 usage error · 3 timeout
"""

__version__ = "2.0.0"

import os
import sys

# --------------------------------------------------------------------------
# Protocol contract (MUST match PlayProtocol.kt — do not edit one side only)
# --------------------------------------------------------------------------
APP_PACKAGE = "com.rumi.controller"

ACTION_PLAY_SPOTIFY = "com.rumi.voiceassistant.PLAY_SPOTIFY"
ACTION_MEDIA_CONTROL = "com.rumi.voiceassistant.MEDIA_CONTROL"
ACTION_VOLUME_CONTROL = "com.rumi.voiceassistant.VOLUME_CONTROL"

EXTRA_QUERY = "query"
EXTRA_COMMAND = "command"

MEDIA_COMMANDS = frozenset(("toggle", "play", "pause", "next", "prev"))
VOLUME_COMMANDS = frozenset(("up", "down", "mute", "auto_lower"))

SPOTIFY_PACKAGE = "com.spotify.music"

# Natural-language aliases → canonical command strings.
_ALIASES = {
    "previous": "prev",
    "back": "prev",
    "skip": "next",
    "resume": "play",
    "unpause": "play",
    "duck": "auto_lower",
    "quieter": "down",
    "louder": "up",
}

_SEND_TIMEOUT_S = 6.0  # never let a wedged `am`/`adb` hang the assistant


class RumiError(Exception):
    """Fatal, user-facing failure (bad transport, send failed)."""


# --------------------------------------------------------------------------
# Transport detection
# --------------------------------------------------------------------------
_cached_transport = None


def _detect_transport():
    """Return 'termux' or 'adb'. Cached for the life of the process.

    Priority: RUMI_TRANSPORT env override → Termux PREFIX → `am` on PATH
    → `adb` on PATH.
    """
    global _cached_transport
    if _cached_transport:
        return _cached_transport

    override = os.environ.get("RUMI_TRANSPORT", "").strip().lower()
    if override in ("termux", "adb"):
        _cached_transport = override
        return override

    if "com.termux" in os.environ.get("PREFIX", ""):
        _cached_transport = "termux"
        return "termux"

    from shutil import which  # lazy: ~1 ms, only when detection is needed

    if which("am"):
        _cached_transport = "termux"
        return "termux"
    if which("adb"):
        _cached_transport = "adb"
        return "adb"

    raise RumiError(
        "no transport found: install Termux (`am`) or platform-tools (`adb`), "
        "or set RUMI_TRANSPORT=termux|adb"
    )


# --------------------------------------------------------------------------
# Broadcast sender
# --------------------------------------------------------------------------
def _build_argv(transport, action, extra_key=None, extra_value=None):
    """Assemble the argv list (no shell, no quoting bugs, faster exec)."""
    argv = ["am", "broadcast", "-a", action, "-p", APP_PACKAGE]
    if extra_key is not None:
        argv += ["-e", extra_key, str(extra_value)]
    if transport == "adb":
        return ["adb", "shell"] + argv
    return argv


def _send(action, extra_key=None, extra_value=None):
    """Fire one broadcast. Raises RumiError on failure."""
    import subprocess  # lazy import keeps CLI startup minimal

    transport = _detect_transport()
    argv = _build_argv(transport, action, extra_key, extra_value)
    try:
        proc = subprocess.run(
            argv,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=_SEND_TIMEOUT_S,
            check=False,
        )
    except FileNotFoundError:
        raise RumiError(
            "transport binary missing for '%s' — set RUMI_TRANSPORT=termux|adb"
            % transport
        )
    except subprocess.TimeoutExpired:
        raise RumiError("broadcast timed out after %.0fs (%s)" % (_SEND_TIMEOUT_S, action))

    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip() or "unknown error"
        raise RumiError("broadcast failed (%s): %s" % (action, detail))
    return transport


# --------------------------------------------------------------------------
# Public command API (importable from the assistant's NLP layer)
# --------------------------------------------------------------------------
def play_spotify(query):
    """One broadcast → app launches Spotify search + accessibility autoplay."""
    query = " ".join(query.split()).strip() if query else ""
    if not query:
        raise RumiError("play requires a non-empty query")
    return _send(ACTION_PLAY_SPOTIFY, EXTRA_QUERY, query)


def media(command):
    command = _ALIASES.get(command, command)
    if command not in MEDIA_COMMANDS:
        raise RumiError(
            "unknown media command %r (want one of: %s)"
            % (command, ", ".join(sorted(MEDIA_COMMANDS)))
        )
    return _send(ACTION_MEDIA_CONTROL, EXTRA_COMMAND, command)


def volume(command):
    command = _ALIASES.get(command, command)
    if command not in VOLUME_COMMANDS:
        raise RumiError(
            "unknown volume command %r (want one of: %s)"
            % (command, ", ".join(sorted(VOLUME_COMMANDS)))
        )
    return _send(ACTION_VOLUME_CONTROL, EXTRA_COMMAND, command)


def handle_text(text):
    """Map one assistant utterance to a bridge command.

    Returns a short human-readable description of what was sent.
    Raises KeyError if the utterance maps to nothing (caller may then
    route it to a different skill).
    """
    parts = text.strip().split()
    if not parts:
        raise KeyError("empty utterance")

    head = parts[0].lower()
    rest = " ".join(parts[1:])

    if head == "play" and rest:
        play_spotify(rest)
        return "spotify: %s" % rest
    if head in ("play", "pause", "toggle", "next", "prev", "previous", "skip", "resume"):
        cmd = _ALIASES.get(head, head)
        if cmd in MEDIA_COMMANDS:
            media(cmd)
            return "media: %s" % cmd
    if head in ("volume", "vol") and len(parts) > 1:
        cmd = _ALIASES.get(parts[1].lower(), parts[1].lower())
        volume(cmd)
        return "volume: %s" % cmd
    if head == "duck":
        volume("auto_lower")
        return "volume: auto_lower"

    raise KeyError("no rumi bridge skill for %r" % text)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
_USAGE = __doc__


def _doctor():
    """Verify transport works and the controller app is installed."""
    import subprocess

    transport = _detect_transport()
    argv = (
        ["adb", "shell", "pm", "path", APP_PACKAGE]
        if transport == "adb"
        else ["pm", "path", APP_PACKAGE]
    )
    try:
        proc = subprocess.run(
            argv, stdin=subprocess.DEVNULL, capture_output=True,
            timeout=_SEND_TIMEOUT_S, check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        raise RumiError("doctor: package query failed: %s" % exc)

    if proc.returncode != 0 or APP_PACKAGE.encode() not in proc.stdout:
        raise RumiError(
            "doctor: %s not installed on device (%s transport)" % (APP_PACKAGE, transport)
        )
    return "doctor ok: transport=%s, %s found" % (transport, APP_PACKAGE)


def _run_one(argv):
    """Execute one tokenized command line (without the program name)."""
    if not argv:
        raise RumiError("empty command (try --help)")

    head = argv[0].lower()

    if head == "play" and len(argv) > 1:
        play_spotify(" ".join(argv[1:]))
        return "spotify: %s" % " ".join(argv[1:])
    if head in MEDIA_COMMANDS or head in ("previous", "skip", "resume"):
        media(head)
        return "media: %s" % _ALIASES.get(head, head)
    if head in ("volume", "vol"):
        if len(argv) != 2:
            raise RumiError("usage: volume up|down|mute|auto_lower")
        volume(argv[1].lower())
        return "volume: %s" % _ALIASES.get(argv[1].lower(), argv[1].lower())
    if head == "duck":
        volume("auto_lower")
        return "volume: auto_lower"
    if head == "raw":
        if len(argv) != 4:
            raise RumiError("usage: raw <action> <extra_key> <extra_value>")
        _send(argv[1], argv[2], argv[3])
        return "raw: %s" % argv[1]

    raise RumiError("unknown command %r (try --help)" % argv[0])


def _loop(transport_flag):
    """Daemon mode: one process, commands arrive on stdin."""
    if transport_flag:
        os.environ["RUMI_TRANSPORT"] = transport_flag
    try:
        _detect_transport()  # fail fast before entering the loop
    except RumiError as exc:
        sys.stderr.write("rumi: %s\n" % exc)
        return 1
    sys.stdout.write("rumi loop ready (transport=%s)\n" % _cached_transport)
    sys.stdout.flush()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line in ("exit", "quit"):
            return 0
        try:
            result = _run_one(line.split())
            sys.stdout.write("ok %s\n" % result)
        except RumiError as exc:
            sys.stdout.write("err %s\n" % exc)
        sys.stdout.flush()
    return 0


def main(args):
    # Strip flags first (keeps the hot path a plain positional list).
    rest = []
    transport_flag = None
    it = iter(range(len(args)))
    skip = False
    for i in it:
        if skip:
            skip = False
            continue
        a = args[i]
        if a == "--transport" and i + 1 < len(args):
            transport_flag = args[i + 1]
            skip = True
        elif a.startswith("--transport="):
            transport_flag = a.split("=", 1)[1]
        else:
            rest.append(a)
    if transport_flag:
        os.environ["RUMI_TRANSPORT"] = transport_flag

    if not rest or rest[0] in ("-h", "--help", "help"):
        sys.stdout.write(_USAGE)
        return 0
    if rest[0] == "--version":
        sys.stdout.write("rumi.py %s\n" % __version__)
        return 0
    if rest[0] == "--loop":
        return _loop(transport_flag)
    if rest[0] == "--doctor":
        try:
            sys.stdout.write(_doctor() + "\n")
            return 0
        except RumiError as exc:
            sys.stderr.write("rumi: %s\n" % exc)
            return 1

    try:
        result = _run_one(rest)
    except RumiError as exc:
        msg = str(exc)
        sys.stderr.write("rumi: %s\n" % msg)
        return 2 if msg.startswith(("usage:", "unknown command")) else 1
    sys.stdout.write("ok %s\n" % result)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
