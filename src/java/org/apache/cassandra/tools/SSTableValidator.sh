#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if [ "x$CASSANDRA_INCLUDE" = "x" ]; then
    for include in /usr/share/cassandra/cassandra.in.sh \
                   /usr/local/share/cassandra/cassandra.in.sh \
                   /opt/cassandra/cassandra.in.sh \
                   ~/.cassandra.in.sh \
                   `dirname $0`/cassandra.in.sh; do
        if [ -r $include ]; then
            . $include
            break
        fi
    done
elif [ -r $CASSANDRA_INCLUDE ]; then
    . $CASSANDRA_INCLUDE
fi

# Use JAVA_HOME if set, otherwise look for java in PATH
if [ -x $JAVA_HOME/bin/java ]; then
    JAVA=$JAVA_HOME/bin/java
else
    JAVA=`which java`
fi

for lib in /one/one-list-storage/deploy/lib/*.jar
do
    cassandra_classpath=":${lib}${cassandra_classpath}"
done
CLASSPATH=".:/one/one-list-storage/deploy/conf:${cassandra_classpath}"

$JAVA -ea -cp $CLASSPATH -Dlog4j.configuration=log4j-tools.properties org.apache.cassandra.tools.SSTableValidator $1

# vi:ai sw=4 ts=4 tw=0 et