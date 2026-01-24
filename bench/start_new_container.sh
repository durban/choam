#!/usr/bin/env bash

# SPDX-License-Identifier: Apache-2.0
# Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Note: run this script from the project root!

set -e -u -o pipefail
IFS=$'\n\t'

_IMAGE_TAG="choam-bench-image"
_RESULTS_DIR="$(pwd)/bench/results"

docker build . -t "$_IMAGE_TAG" -f bench/Dockerfile

docker run -it --rm --privileged \
  --mount type=bind,src="$_RESULTS_DIR",dst="/root/choam/bench/results" \
  "$_IMAGE_TAG" \
  /usr/bin/bash
