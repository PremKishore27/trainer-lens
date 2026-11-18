#!/bin/sh
# Compiles the core with the test checks and runs them. Needs a Mac with Xcode or the Swift toolchain.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/trainerlens-core-tests"
swiftc -O -o "$OUT" ../Core/*.swift main.swift
"$OUT" data
