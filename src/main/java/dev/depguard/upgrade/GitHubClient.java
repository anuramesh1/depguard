package dev.depguard.upgrade;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * The few GitHub REST calls an upgrade pull request needs: read a file, create a branch, commit a file, open a PR.
 * Plain HTTPS with a token, no git binary and no clone, which suits Lambda.
 */
public class GitHubClient {

    private final RestClient rest;
    private final GitHubProperties github;

    public GitHubClient(RestClient.Builder builder, GitHubProperties github) {
        this.github = github;
        RestClient.Builder b = builder.clone()
                .baseUrl(github.apiUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");
        if (github.hasToken()) {
            b = b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + github.token());
        }
        this.rest = b.build();
    }

    /** A file's text and its blob SHA (needed to update it, and to notice if it changed meanwhile). */
    public record RepoFile(String content, String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentResponse(String content, String sha, String encoding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefResponse(RefObject object) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefObject(String sha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullResponse(String html_url, int number) {
    }

    /**
     * Builds /repos/{owner}/{repo}/... from path segments. Names like "owner/repo" or "app/pom.xml" are split on "/"
     * first: passed whole as one URI variable, Spring would encode the "/" as %2F and GitHub would answer 404.
     */
    private static URI repoUrl(UriBuilder uri, String repository, String... parts) {
        List<String> segments = new ArrayList<>(List.of("repos"));
        segments.addAll(List.of(repository.split("/")));
        for (String part : parts) {
            segments.addAll(List.of(part.split("/")));
        }
        return uri.pathSegment(segments.toArray(String[]::new)).build();
    }

    public RepoFile readFile(String path, String branch) {
        ContentResponse response = rest.get()
                .uri(u -> repoUrl(u.queryParam("ref", branch), github.repository(), "contents", path))
                .retrieve().body(ContentResponse.class);
        String text = new String(Base64.getMimeDecoder().decode(response.content()), StandardCharsets.UTF_8);
        return new RepoFile(text, response.sha());
    }

    public String branchHead(String branch) {
        return rest.get().uri(u -> repoUrl(u, github.repository(), "git", "ref", "heads", branch))
                .retrieve().body(RefResponse.class).object().sha();
    }

    public void createBranch(String name, String fromSha) {
        rest.post().uri(u -> repoUrl(u, github.repository(), "git", "refs"))
                .body(Map.of("ref", "refs/heads/" + name, "sha", fromSha))
                .retrieve().toBodilessEntity();
    }

    /** Commits a new version of a file; GitHub rejects it if {@code currentSha} is no longer the file's SHA. */
    public void commitFile(String path, String branch, String content, String currentSha, String message) {
        rest.put().uri(u -> repoUrl(u, github.repository(), "contents", path))
                .body(Map.of("message", message, "branch", branch, "sha", currentSha,
                        "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))))
                .retrieve().toBodilessEntity();
    }

    /** Opens a pull request and returns its web URL. */
    public String openPullRequest(String head, String base, String title, String body) {
        PullResponse pull = rest.post().uri(u -> repoUrl(u, github.repository(), "pulls"))
                .body(Map.of("head", head, "base", base, "title", title, "body", body))
                .retrieve().body(PullResponse.class);
        return pull.html_url();
    }
}
