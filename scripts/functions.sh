# -*- coding: utf-8 -*-

proj_meta() {
    local proj_root="$1"
    local grp="$2"
    local sin="$(head -n1 ${proj_root}/project.clj)"
    read -r -d '' regexp  <<'EOF'
^\(defproject\s+(?P<clojars_group>\w+)/(?P<name>\w+)\s+\"(?P<version>.+)\"
EOF
    read -r -d '' src <<EOF
import re;
import sys;
print re.sub(*sys.argv[1:])
EOF
    python2 -c "${src}" "${regexp}" "\g<${grp}>" "$sin"
}

run_step() {
    local msg="$1"
    inform "${msg}" -n
    shift
    $@ || (echo "❌" && exit 1)
    echo "✓"
}

release_file() {
    local release="$1"
    local source_path="$2"
    local target_path="$3"
    git show "${release}:${source_path}" > "${target_path}"
    chmod u+x "${target_path}"
}

divider() {
    local msg="$1"
    let msg_len=${#msg}
    printf '_%.0s' $(seq 1 ${msg_len})
    echo
}

inform() {
    local msg="🐛 $1"
    shift
    local flags="$*"
    echo "$flags" "${msg}"
}
