#!/bin/bash
KEYSTORE="app/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "Creating fixed keystore..."
    keytool -genkey -v -keystore "$KEYSTORE" -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
fi
