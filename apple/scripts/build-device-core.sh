#!/usr/bin/env bash
# Cold build for a physical iPhone, both watch architectures and optionally the Mac tests.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
tasks=(:core:linkReleaseFrameworkIosArm64 :core:assembleReleaseWatchosFatFrameworkForReclyCoreXCFramework)
frameworks=(-framework "$root/core/build/bin/iosArm64/releaseFramework/ReclyCore.framework"
            -framework "$root/core/build/ReclyCoreXCFrameworkTemp/fatframework/release/watchos/ReclyCore.framework")
slices="ios-arm64 watchos-arm64_arm64_32"
if [[ ${INCLUDE_MAC:-0} == 1 ]]; then
    tasks+=(:core:linkReleaseFrameworkMacosArm64)
    frameworks+=(-framework "$root/core/build/bin/macosArm64/releaseFramework/ReclyCore.framework")
    slices+=" macos-arm64"
fi
./gradlew --max-workers=1 --no-daemon "-Dorg.gradle.jvmargs=${DEVICE_CORE_JVM_ARGS:--Xmx3g}" "${tasks[@]}" "$@"

dir="$root/apple/RecKit/Frameworks"
mkdir -p "$dir"
stage=$(mktemp -d "$dir/.device-build.XXXXXXXX")
trap 'rm -rf "$stage"' EXIT
xcodebuild -create-xcframework "${frameworks[@]}" -output "$stage/ReclyCore.xcframework"
for slice in $slices; do
    test -s "$stage/ReclyCore.xcframework/$slice/ReclyCore.framework/ReclyCore"
done
rsync -a --delete "$stage/ReclyCore.xcframework/" "$dir/ReclyCore.xcframework/"
printf '%s\n' "$slices" > "$dir/ReclyCore.partial"
"$root/apple/scripts/fetch-speaker-models.sh"
