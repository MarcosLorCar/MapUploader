#!/usr/bin/env bash
#
# MapUploader quick installer (Linux / macOS)
#
#   curl -fsSL https://raw.githubusercontent.com/MarcosLorCar/MapUploader/main/install.sh | bash
#
# Run this from your Minecraft server directory (the folder containing
# server.properties). Two install modes:
#
#   proxy  - MapUploader takes the place of your server jar and launches the real
#            server as a child process, so a single start command runs both and
#            they share one lifecycle. Your in-game maps "just work".
#   normal - MapUploader is installed as a standalone web app with its own start
#            script; you run it next to the server yourself.
#
# Flags (optional; prompts are used when omitted):
#   --mode proxy|normal     install mode
#   --jar  <file.jar>        which jar to proxy (proxy mode, when several exist)
#   --port <n>               web UI port (default 8080)
#   --version <tag>          release tag to install (default: latest)
#   --no-datapack            skip auto-installing the datapack
#   --yes                    assume "yes" for confirmations
#   --help
#
set -euo pipefail

REPO="MarcosLorCar/MapUploader"
API="https://api.github.com/repos/${REPO}"
RAW="https://raw.githubusercontent.com/${REPO}/main"

MODE=""; JAR=""; WEB_PORT="8080"; VERSION="latest"; INSTALL_DATAPACK=1; ASSUME_YES=0

# ----------------------------------------------------------------------------- helpers
c_blue=$'\033[34m'; c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_reset=$'\033[0m'
log()  { printf '%s[MapUploader]%s %s\n' "$c_blue"  "$c_reset" "$*"; }
ok()   { printf '%s[MapUploader]%s %s\n' "$c_green" "$c_reset" "$*"; }
warn() { printf '%s[MapUploader]%s %s\n' "$c_yellow" "$c_reset" "$*"; }
die()  { printf '%s[MapUploader]%s %s\n' "$c_red"   "$c_reset" "$*" >&2; exit 1; }

# Read a line from the real terminal even when this script is piped from curl.
prompt() {
    local message="$1" default="${2:-}" reply
    if [ -r /dev/tty ]; then read -r -p "$message" reply < /dev/tty || true
    else read -r -p "$message" reply || true; fi
    printf '%s' "${reply:-$default}"
}
confirm() { # confirm "question" -> 0 for yes
    [ "$ASSUME_YES" = 1 ] && return 0
    local a; a="$(prompt "$1 [y/N] " "")"; case "$a" in y|Y|yes|YES) return 0;; *) return 1;; esac
}

# server.properties access ----------------------------------------------------------
SP="server.properties"
prop() { # prop key default
    local v=""; [ -f "$SP" ] && v="$(grep -E "^$1=" "$SP" | head -1 | cut -d= -f2- || true)"
    printf '%s' "${v:-$2}"
}
set_prop() { # set_prop key value  (update in place or append)
    local key="$1" val="$2"
    if grep -qE "^$key=" "$SP"; then
        # use a temp file to stay portable across sed flavours
        grep -vE "^$key=" "$SP" > "$SP.tmp"; printf '%s=%s\n' "$key" "$val" >> "$SP.tmp"; mv "$SP.tmp" "$SP"
    else
        printf '%s=%s\n' "$key" "$val" >> "$SP"
    fi
}

# ----------------------------------------------------------------------------- args
while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE="${2:-}"; shift 2;;
        --jar) JAR="${2:-}"; shift 2;;
        --port) WEB_PORT="${2:-}"; shift 2;;
        --version) VERSION="${2:-}"; shift 2;;
        --no-datapack) INSTALL_DATAPACK=0; shift;;
        --yes|-y) ASSUME_YES=1; shift;;
        --help|-h) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
        *) die "Unknown option: $1";;
    esac
done

# ----------------------------------------------------------------------------- checks
command -v curl >/dev/null 2>&1 || die "curl is required."
command -v java >/dev/null 2>&1 || die "Java is not installed. MapUploader needs Java 17 or newer."
JV="$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)"
[ "${JV:-0}" -ge 17 ] 2>/dev/null || die "Java 17+ is required (found: $(java -version 2>&1 | head -1))."

[ -f "$SP" ] || warn "No server.properties here. Run this from your server directory for auto-config."

# ----------------------------------------------------------------------------- release lookup
release_json() {
    if [ "$VERSION" = "latest" ]; then curl -fsSL "${API}/releases/latest"
    else curl -fsSL "${API}/releases/tags/${VERSION}"; fi
}
asset_url() { # asset_url <regex>  -> browser_download_url matching regex
    release_json | grep -oE '"browser_download_url": *"[^"]*"' \
        | sed -E 's/.*"(https[^"]*)"/\1/' | grep -E "$1" | head -1
}
download() { log "Downloading $(basename "$2")..."; curl -fSL --progress-bar "$1" -o "$2"; }

JAR_URL="$(asset_url '\.jar$' || true)"
[ -n "$JAR_URL" ] || die "Could not find a .jar asset in the '$VERSION' release of $REPO."

# ----------------------------------------------------------------------------- mode
if [ -z "$MODE" ]; then
    echo
    log "Choose an installation type (docs: https://github.com/${REPO}/blob/main/docs/INSTALLATION.md):"
    echo "   1) proxy  - one command starts the server + MapUploader together (recommended)"
    echo "   2) normal - standalone web app with its own start script"
    case "$(prompt 'Enter 1 or 2: ' '1')" in 2) MODE="normal";; *) MODE="proxy";; esac
fi

install_datapack() {
    [ "$INSTALL_DATAPACK" = 1 ] || return 0
    local level dpdir url
    level="$(prop level-name world)"; dpdir="${level}/datapacks"
    url="$(asset_url 'MapUploader\.zip$' || true)"; [ -n "$url" ] || url="${RAW}/MapUploader.zip"
    mkdir -p "$dpdir"
    download "$url" "${dpdir}/MapUploader.zip"
    ok "Datapack installed to ${dpdir}/. Run /reload (or restart) to enable it."
}

# ----------------------------------------------------------------------------- normal
if [ "$MODE" = "normal" ]; then
    mkdir -p mapuploader
    download "$JAR_URL" "mapuploader/mapuploader.jar"
    level="$(prop level-name world)"
    cat > start-mapuploader.sh <<EOF
#!/usr/bin/env bash
# Standalone MapUploader web app launcher (generated by install.sh).
cd "\$(dirname "\$0")"
export MC_WORLD_DATA_PATH="\$(pwd)/${level}/data/minecraft/maps"
export MC_RCON_HOST="127.0.0.1"
export MC_RCON_PORT="$(prop rcon.port 25575)"
export MC_RCON_PASSWORD="$(prop rcon.password '')"
export MAPUPLOADER_WEB_PORT="${WEB_PORT}"
exec java -jar mapuploader/mapuploader.jar "\$@"
EOF
    chmod +x start-mapuploader.sh
    install_datapack
    echo
    ok "Done. Start the web app with:  ./start-mapuploader.sh"
    [ -z "$(prop rcon.password '')" ] && warn "RCON password is empty in server.properties; in-game map delivery will fail until it is set."
    exit 0
fi

# ----------------------------------------------------------------------------- proxy
echo
warn "Proxy install must run while the Minecraft server is STOPPED (the jar gets renamed)."
confirm "Is the server stopped and ready?" || die "Stop the server, then run this again."

# refuse to double-install
if ls proxied_*.jar >/dev/null 2>&1; then
    die "A 'proxied_*.jar' already exists here — MapUploader looks already installed. Run uninstall-mapuploader.sh first."
fi

# pick the server jar
if [ -z "$JAR" ]; then
    CANDIDATES=()
    while IFS= read -r line; do [ -n "$line" ] && CANDIDATES+=("$line"); done \
        < <(ls -1 *.jar 2>/dev/null | grep -v '^proxied_' || true)
    [ "${#CANDIDATES[@]}" -gt 0 ] || die "No .jar files found here. Run this from your server directory."
    if [ "${#CANDIDATES[@]}" -eq 1 ]; then
        JAR="${CANDIDATES[0]}"; log "Found one server jar: $JAR"
    else
        log "Multiple jars found — which one is your Minecraft server?"
        i=1; for j in "${CANDIDATES[@]}"; do echo "   $i) $j"; i=$((i+1)); done
        sel="$(prompt 'Enter number: ' '1')"; JAR="${CANDIDATES[$((sel-1))]:-}"
    fi
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || die "Invalid jar selection: '$JAR'"

# RCON: required for graceful (cross-platform) shutdown and in-game map delivery
if [ "$(prop enable-rcon false)" != "true" ] || [ -z "$(prop rcon.password '')" ]; then
    echo
    warn "Proxy mode needs RCON enabled so MapUploader can stop the server safely and deliver maps."
    echo  "   It requires these in server.properties:"
    echo  "     enable-rcon=true"
    echo  "     rcon.port=$(prop rcon.port 25575)"
    echo  "     rcon.password=<a password>"
    if [ -f "$SP" ] && confirm "Let the installer set these now (backs up server.properties, generates a random password)?"; then
        cp "$SP" "$SP.bak.$(date +%s)"
        # Finite producer first so nothing reading /dev/urandom gets SIGPIPE under pipefail.
        PW="$(head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 20)"
        [ -n "$PW" ] || PW="change_me_$RANDOM$RANDOM"
        set_prop enable-rcon true
        set_prop rcon.port "$(prop rcon.port 25575)"
        set_prop rcon.password "$PW"
        ok "RCON enabled (password written to server.properties)."
    else
        die "Aborting. Enable RCON in server.properties, then run this again."
    fi
fi

# download to a staging file first and verify it is a launcher-capable jar BEFORE
# touching the real server jar (older releases have no proxy launcher).
download "$JAR_URL" ".mapuploader.dl"
if command -v unzip >/dev/null 2>&1; then
    unzip -p ".mapuploader.dl" META-INF/MANIFEST.MF 2>/dev/null | grep -q 'me.orange.LauncherKt' \
        || { rm -f ".mapuploader.dl"; die "The latest release jar has no proxy launcher. Update to MapUploader v1.2.0 or newer."; }
fi

# swap jar identity: real server -> proxied_<name>, wrapper (staged) -> <name>
cp "$JAR" "${JAR}.bak.$(date +%s)"
mv "$JAR" "proxied_${JAR}"
mv ".mapuploader.dl" "$JAR"
ok "Installed: your start command's '-jar $JAR' now launches MapUploader, which runs 'proxied_${JAR}'."

# uninstaller
cat > uninstall-mapuploader.sh <<EOF
#!/usr/bin/env bash
# Reverts the MapUploader proxy install.
set -e
cd "\$(dirname "\$0")"
[ -f "proxied_${JAR}" ] || { echo "Nothing to revert (proxied_${JAR} not found)."; exit 1; }
rm -f "${JAR}"
mv "proxied_${JAR}" "${JAR}"
echo "Reverted: ${JAR} is the original server jar again. (Datapack and server.properties left untouched.)"
EOF
chmod +x uninstall-mapuploader.sh

install_datapack
echo
ok "All set. Start your server the way you normally do — MapUploader comes up with it."
log "Web UI: http://<host>:${WEB_PORT}/ (use /trigger UploadMap in-game for your link)."
log "To revert:  ./uninstall-mapuploader.sh"
