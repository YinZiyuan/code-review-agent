#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 <application-image>" >&2
    exit 2
fi

image=$1

docker run --rm --entrypoint sh "$image" -ceu '
require_command() {
    command -v "$1" >/dev/null || {
        echo "runtime-capability-smoke: missing $1" >&2
        exit 1
    }
}

require_command javac
java --list-modules | grep -q "^jdk.compiler@" || {
    echo "runtime-capability-smoke: missing jdk.compiler module" >&2
    exit 1
}
require_command spotbugs
spotbugs -version >/dev/null

fixture_dir=$(mktemp -d)
trap "rm -rf \"$fixture_dir\"" EXIT
mkdir -p "$fixture_dir/application" "$fixture_dir/source" "$fixture_dir/smoke-classes"
printf "%s\n" \
    "public final class ReviewRuntimeFixture {" \
    "    public int identity(int value) { return value; }" \
    "}" > "$fixture_dir/source/ReviewRuntimeFixture.java"

cd "$fixture_dir/application"
jar xf /app/code-review-agent.jar
cd "$fixture_dir"
application_classpath="$fixture_dir/application/BOOT-INF/classes:$fixture_dir/application/BOOT-INF/lib/*"
printf "%s\n" \
    "import dev.langchain4j.example.codereview.analyzer.SourceCompiler;" \
    "import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;" \
    "import dev.langchain4j.example.codereview.config.AgentConfig;" \
    "import java.nio.file.Path;" \
    "import java.util.List;" \
    "public final class ReviewRuntimeSmoke {" \
    "    public static void main(String[] args) {" \
    "        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(" \
    "                new AgentConfig().spotBugsRunner(), new SourceCompiler());" \
    "        if (!analyzer.analyzeWithSource(List.of(), Path.of(args[0])).ran()) {" \
    "            throw new AssertionError(\"compilable fixture took the SpotBugs skip path\");" \
    "        }" \
    "    }" \
    "}" > "$fixture_dir/ReviewRuntimeSmoke.java"
javac -cp "$application_classpath" \
    -d "$fixture_dir/smoke-classes" "$fixture_dir/ReviewRuntimeSmoke.java"
java -cp "$fixture_dir/smoke-classes:$application_classpath" \
    ReviewRuntimeSmoke "$fixture_dir/source"
'

echo "runtime-capability-smoke: PASS (javac + jdk.compiler + SpotBugs analyzed a compilable fixture)"
