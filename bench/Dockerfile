# SPDX-License-Identifier: Apache-2.0
# Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

# Dockerfile for creating a container image
# for more reproducible benchmark results.
#
# To build the image, and run a container,
# run the bench/start_new_container.sh
# script from the project root.

FROM sbtscala/scala-sbt:eclipse-temurin-24.0.1_9_1.11.2_2.13.16

COPY . /root/choam
WORKDIR /root/choam
RUN sbt update
RUN sbt "measurePerformance -lp"
