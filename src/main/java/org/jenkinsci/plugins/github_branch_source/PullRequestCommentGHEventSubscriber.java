/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.github_branch_source;

import com.cloudbees.jenkins.GitHubRepositoryName;
import hudson.Extension;
import hudson.model.Item;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.kohsuke.github.GHEvent.ISSUE_COMMENT;
import static org.kohsuke.github.GHEvent.PULL_REQUEST_REVIEW_COMMENT;

/**
 * This subscriber manages {@link GHEvent} PULL_REQUEST.
 */
@Extension
public class PullRequestCommentGHEventSubscriber extends GHEventsSubscriber {

    private static final Logger LOGGER = Logger.getLogger(PullRequestCommentGHEventSubscriber.class.getName());
    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");
    private final GitHubProvider gitHubProvider;

    PullRequestCommentGHEventSubscriber(GitHubProvider gitHubProvider) {
        this.gitHubProvider = gitHubProvider;
    }

    public PullRequestCommentGHEventSubscriber() {
        this.gitHubProvider = new GitHubProvider() {
            @Override
            public void useGitHubForApi(String api, Consumer<GitHub> consumer) {
                Connector.useGitHub(api, consumer);
            }
        };
    }


    @Override
    protected boolean isApplicable(@Nullable Item project) {
        return isBuildablePr(project);
    }

    static boolean isBuildablePr(@Nullable Item project) {
        if (project != null) {
            if (project instanceof SCMSourceOwner) {
                SCMSourceOwner owner = (SCMSourceOwner) project;
                for (SCMSource source : owner.getSCMSources()) {
                    if (source instanceof GitHubSCMSource) {
                        return true;
                    }
                }
            }
            if (project.getParent() instanceof SCMSourceOwner) {
                SCMSourceOwner owner = (SCMSourceOwner) project.getParent();
                for (SCMSource source : owner.getSCMSources()) {
                    if (source instanceof GitHubSCMSource) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @return set with [ PULL_REQUEST_REVIEW_COMMENT, ISSUE_COMMENT ] event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST_REVIEW_COMMENT, ISSUE_COMMENT);
    }

    @Override
    protected void onEvent(GHSubscriberEvent event) {
        try {
            final GHEventPayload.IssueComment p = GitHub.offline()
                    .parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.IssueComment.class);

            String action = p.getAction();
            String repoUrl = p.getRepository().getHtmlUrl().toExternalForm();
            LOGGER.log(Level.FINE, "Received {0} for {1} from {2}",
                    new Object[]{event.getGHEvent(), repoUrl, event.getOrigin()}
            );

            Matcher matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
            if (matcher.matches()) {
                final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
                if (changedRepository != null && Objects.equals(action, "created")) {
                    gitHubProvider.useGitHubForApi(p.getRepository().getUrl().getHost(), gitHub -> {
                        try {
                            GitHubRepositoryName repo = GitHubRepositoryName.create(repoUrl);
                            if (repo != null) {
                                GHPullRequest prCommented = gitHub.getRepository(p.getRepository().getName()).getPullRequest(p.getIssue().getNumber());
                                BuildablePullRequestSCMHeadEventImpl scmEvent = new BuildablePullRequestSCMHeadEventImpl(
                                        SCMEvent.Type.REMOVED,
                                        event.getTimestamp(),
                                        new PullRequestGHEventSubscriber.BuildablePullRequest("synchronize", p.getIssue().getNumber(), prCommented, p.getRepository()),
                                        repo,
                                        event.getOrigin()
                                );
                                fireAfterDelay(scmEvent);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                }
            }
        } catch (IOException e) {
            LogRecord lr = new LogRecord(Level.WARNING, "Could not parse {0} event from {1} with payload: {2}");
            lr.setParameters(new Object[]{event.getGHEvent(), event.getOrigin(), event.getPayload()});
            lr.setThrown(e);
            LOGGER.log(lr);
        }
    }

    private void fireAfterDelay(final BuildablePullRequestSCMHeadEventImpl e) {
        SCMHeadEvent.fireLater(e, GitHubSCMSource.getEventDelaySeconds(), TimeUnit.SECONDS);
    }

    private abstract static class GitHubProvider {
        public abstract void useGitHubForApi(String api, Consumer<GitHub> consumer);
    }
}


