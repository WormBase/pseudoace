# -*- coding: utf-8 -*-

value_from_pom() {
    local key=$1
    xml2 < pom.xml  | grep "/project/${key}=" | cut -d= -f2
}

proj_version() {
    value_from_pom "version"
}

proj_name() {
    value_from_pom "artifactId"
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
