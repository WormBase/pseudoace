#!/bin/bash

read -a clojars_username -p "Enter your clojars username:" 
read -a clojars_password -p "Enter your clojars password:" -s
cat <<-EOF > ~/.m2/settings.xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                      https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <servers>
    <server>
      <id>clojars</id>
      <username>${clojars_username}</username>
      <password>${clojars_password}</password>
    </server>
  </servers>
</settings>
EOF
