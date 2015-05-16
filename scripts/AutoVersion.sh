#! /bin/bash
ME="`readlink -f "${0}"`"
PROG="${ME##*/}"
DIR="${ME%${PROG}}"
DIR="${DIR%%/}"
[ -z "${DIR}" ] && DIR='/'
cd "${DIR}/../"
OUT='res/values/auto-build.xml'

# avoid overwriting custom versions
grep -i -- 'custom' "${OUT}" > /dev/null 2>&1 && \
	exit 0

# Use the fancy id generator if present, fall back to plain "hg id" if not.
Id="`Hg-ident 2> /dev/null`"
[ -z "${Id}" ] && Id="`hg id 2> /dev/null`"

Ver="${Id}"
[ -n "${Ver}" ] && Ver="${Ver} "
Ver="${Ver}`date '+%Y%m%d.%H%M%S'`"

echo "Creating auto-version data..."
exec > "${OUT}"
cat <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Auto-generated file, do not edit -->
<resources xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2" tools:ignore="UnusedResources">
	<string name="auto_build" translatable="false">${Ver}</string>
</resources>
EOF

exit "${?}"
