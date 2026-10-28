plugin := "intellij-diff-navigator"
jetbrains_dir := env("JETBRAINS_DIR", home_directory() / "Library/Application Support/JetBrains")

# List recipes.
default:
    @just --list

# Run the unit tests.
test:
    mise exec -- ./gradlew test

# Build the plugin zip.
build:
    mise exec -- ./gradlew buildPlugin

# Check the plugin against the GoLand in /Applications.
verify:
    mise exec -- ./gradlew verifyPlugin

# Install into the JetBrains IDEs you pick. Each loads it on its next restart.
install: build
    scripts/plugin install "{{jetbrains_dir}}" "{{plugin}}"

# Remove from the JetBrains IDEs you pick. Each unloads it on its next restart.
uninstall:
    scripts/plugin uninstall "{{jetbrains_dir}}" "{{plugin}}"
