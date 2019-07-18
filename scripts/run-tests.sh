#!/bin/bash

for clj_version in 1.9 1.10; do
    for datomic_flavour in free pro; do
	clj -A:${clj_version}:datomic-${datomic_flavour}:test
    done
done
