#!/bin/bash

# Taken and adapted from: https://github.com/ajdm/travis-build-children

# Get last child project build number
BUILD_NUM=$(curl -s 'https://api.travis-ci.org/repos/scalameter/scalameter-examples/builds' | grep -o '^\[{"id":[0-9]*,' | grep -o '[0-9]' | tr -d '\n')
# Restart last child project build
curl -X POST https://api.travis-ci.org/builds/$BUILD_NUM/restart --header "Authorization: token "$AUTH_TOKEN
