#!/bin/sh

edn_path="$1"

log_dir="$(dirname $edn_path)"

if [ ! -d "$log_dir" ]; then
    echo "Log directory '$log_dir' does not exist"
    exit 1
fi

log_filename="$(basename $edn_path)"

if [ "$log_filename" = "helper.edn.gz" ]; then
    exit 0
fi

sorted_filename="$(echo $log_filename | sed 's/.gz$/.sort.gz/')"

tmp_sort_dir="sort-temp$$"

cd $log_dir

mkdir -p "$tmp_sort_dir"

gzip -dc "$log_filename" \
    | sort -T "$tmp_sort_dir" -k 1,1 -s \
    | gzip -c > "$sorted_filename"

if [ $? -eq 0 ] && [ -f "$sorted_filename" ]; then
    rm "$log_filename"
else
    echo "Error sorting $log_filename"
    exit 1
fi
rmdir "$tmp_sort_dir"
