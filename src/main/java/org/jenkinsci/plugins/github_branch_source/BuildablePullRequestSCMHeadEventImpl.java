package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.scm.SCM;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.*;
import jenkins.scm.api.mixin.ChangeRequestCheckoutStrategy;
import jenkins.scm.api.trait.SCMHeadPrefilter;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

import java.io.IOException;
import java.util.*;

class BuildablePullRequestSCMHeadEventImpl extends SCMHeadEvent<PullRequestGHEventSubscriber.BuildablePullRequest> {
    private final String repoHost;
    private final String repoOwner;
    private final String repository;

    public BuildablePullRequestSCMHeadEventImpl(Type type, long timestamp, PullRequestGHEventSubscriber.BuildablePullRequest pullRequest, GitHubRepositoryName repo,
                                                String origin) {
        super(type, timestamp, pullRequest, origin);
        this.repoHost = repo.getHost();
        this.repoOwner = pullRequest.getRepository().getOwnerName();
        this.repository = pullRequest.getRepository().getName();
    }

    private boolean isApiMatch(String apiUri) {
        return repoHost.equalsIgnoreCase(RepositoryUriResolver.hostnameFromApiUri(apiUri));
    }

    @Override
    public boolean isMatch(@NonNull SCMNavigator navigator) {
        return navigator instanceof GitHubSCMNavigator
                && repoOwner.equalsIgnoreCase(((GitHubSCMNavigator) navigator).getRepoOwner());
    }

    @Override
    public String descriptionFor(@NonNull SCMNavigator navigator) {
        String action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case "opened":
                    return "Pull request #" + getPayload().getNumber() + " opened in repository " + repository;
                case "reopened":
                    return "Pull request #" + getPayload().getNumber() + " reopened in repository " + repository;
                case "synchronize":
                    return "Pull request #" + getPayload().getNumber() + " updated in repository " + repository;
                case "closed":
                    return "Pull request #" + getPayload().getNumber() + " closed in repository " + repository;
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event in repository " + repository;
    }

    @Override
    public String descriptionFor(SCMSource source) {
        String action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case "opened":
                    return "Pull request #" + getPayload().getNumber() + " opened";
                case "reopened":
                    return "Pull request #" + getPayload().getNumber() + " reopened";
                case "synchronize":
                    return "Pull request #" + getPayload().getNumber() + " updated";
                case "closed":
                    return "Pull request #" + getPayload().getNumber() + " closed";
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event";
    }

    @Override
    public String description() {
        String action = getPayload().getAction();
        if (action != null) {
            switch (action) {
                case "opened":
                    return "Pull request #" + getPayload().getNumber() + " opened in repository " + repoOwner + "/" + repository;
                case "reopened":
                    return "Pull request #" + getPayload().getNumber() + " reopened in repository " + repoOwner
                            + "/" + repository;
                case "synchronize":
                    return "Pull request #" + getPayload().getNumber() + " updated in repository " + repoOwner + "/"
                            + repository;
                case "closed":
                    return "Pull request #" + getPayload().getNumber() + " closed in repository " + repoOwner + "/"
                            + repository;
            }
        }
        return "Pull request #" + getPayload().getNumber() + " event in repository " + repoOwner + "/" + repository;
    }

    @NonNull
    @Override
    public String getSourceName() {
        return repository;
    }

    @NonNull
    @Override
    public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
        if (!(source instanceof GitHubSCMSource
                && isApiMatch(((GitHubSCMSource) source).getApiUri())
                && repoOwner.equalsIgnoreCase(((GitHubSCMSource) source).getRepoOwner())
                && repository.equalsIgnoreCase(((GitHubSCMSource) source).getRepository()))) {
            return Collections.emptyMap();
        }
        GitHubSCMSource src = (GitHubSCMSource) source;
        PullRequestGHEventSubscriber.BuildablePullRequest pullRequest = getPayload();
        GHPullRequest ghPullRequest = pullRequest.getPullRequest();
        GHRepository repo = pullRequest.getRepository();
        String prRepoName = repo.getName();
        if (!prRepoName.matches(GitHubSCMSource.VALID_GITHUB_REPO_NAME)) {
            // fake repository name
            return Collections.emptyMap();
        }
        GHUser user;
        try {
            user = ghPullRequest.getHead().getUser();
        } catch (IOException e) {
            // fake owner name
            return Collections.emptyMap();
        }
        String prOwnerName = user.getLogin();
        if (!prOwnerName.matches(GitHubSCMSource.VALID_GITHUB_USER_NAME)) {
            // fake owner name
            return Collections.emptyMap();
        }
        if (!ghPullRequest.getBase().getSha().matches(GitHubSCMSource.VALID_GIT_SHA1)) {
            // fake base sha1
            return Collections.emptyMap();
        }
        if (!ghPullRequest.getHead().getSha().matches(GitHubSCMSource.VALID_GIT_SHA1)) {
            // fake head sha1
            return Collections.emptyMap();
        }

        boolean fork = !src.getRepoOwner().equalsIgnoreCase(prOwnerName);

        Map<SCMHead, SCMRevision> result = new HashMap<>();
        GitHubSCMSourceContext context = new GitHubSCMSourceContext(null, SCMHeadObserver.none())
                        .withTraits(src.getTraits());
        if (!fork && context.wantBranches()) {
            final String branchName = ghPullRequest.getHead().getRef();
            SCMHead head = new BranchSCMHead(branchName);
            boolean excluded = false;
            for (SCMHeadPrefilter prefilter: context.prefilters()) {
                if (prefilter.isExcluded(source, head)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                SCMRevision hash =
                        new AbstractGitSCMSource.SCMRevisionImpl(head, ghPullRequest.getHead().getSha());
                result.put(head, hash);
            }
        }
        if (context.wantPRs()) {
            int number = pullRequest.getNumber();
            Set<ChangeRequestCheckoutStrategy> strategies = fork ? context.forkPRStrategies() : context.originPRStrategies();
            for (ChangeRequestCheckoutStrategy strategy: strategies) {
                final String branchName;
                if (strategies.size() == 1) {
                    branchName = "PR-" + number;
                } else {
                    branchName = "PR-" + number + "-" + strategy.name().toLowerCase(Locale.ENGLISH);
                }
                PullRequestSCMHead head;
                PullRequestSCMRevision revision;
                switch (strategy) {
                    case MERGE:
                        // it will take a call to GitHub to get the merge commit, so let the event receiver poll
                        head = new PullRequestSCMHead(ghPullRequest, branchName, true);
                        revision = null;
                        break;
                    default:
                        // Give the event receiver the data we have so they can revalidate
                        head = new PullRequestSCMHead(ghPullRequest, branchName, false);
                        revision = new PullRequestSCMRevision(
                                head,
                                ghPullRequest.getBase().getSha(),
                                ghPullRequest.getHead().getSha()
                        );
                        break;
                }
                boolean excluded = false;
                for (SCMHeadPrefilter prefilter : context.prefilters()) {
                    if (prefilter.isExcluded(source, head)) {
                        excluded = true;
                        break;
                    }
                }
                if (!excluded) {
                    result.put(head, revision);
                }
            }
        }
        return result;
    }

    @Override
    public boolean isMatch(@NonNull SCM scm) {
        return false;
    }
}
