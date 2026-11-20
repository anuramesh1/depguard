package dev.depguard.dependency;

/** One dependency declared in a pom.xml, with its version already resolved. */
public record Dependency(String groupId, String artifactId, String version, String scope) {

    /** OSV names Maven packages as "groupId:artifactId". */
    public String coordinates() {
        return groupId + ":" + artifactId;
    }
}
