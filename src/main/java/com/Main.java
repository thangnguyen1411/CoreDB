package com;

/**
 * This file is not used.
 *
 * The root pom.xml is a parent POM (packaging=pom) — nothing in src/ is compiled.
 *
 * HOW TO START COREDB
 * -------------------
 * 1. Build:
 *      mvn package -q
 *
 * 2. Run the interactive shell:
 *      java -jar coredb-core/target/coredb.jar
 *
 *    With a custom config file:
 *      java -jar coredb-core/target/coredb.jar /path/to/myconfig.yaml
 *
 * Configuration is read from coredb.yaml in the current directory.
 * A sample coredb.yaml is in the project root — edit it to change the
 * data path, engine type, or page size.
 *
 * Entry point: com.coredb.shell.CoreDBShell (in coredb-core)
 */
public class Main {
    private Main() {}
}
