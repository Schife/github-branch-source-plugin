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

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHeadEvent;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHEventPayload;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import static com.google.common.collect.Sets.immutableEnumSet;
import static org.jenkinsci.plugins.github_branch_source.PullRequestCommentGHEventSubscriber.isBuildablePr;
import static org.kohsuke.github.GHEvent.PULL_REQUEST;

/**
 * This subscriber manages {@link org.kohsuke.github.GHEvent} PULL_REQUEST.
 */
@Extension
public class PullRequestGHEventSubscriber extends GHEventsSubscriber {

    private static final Logger LOGGER = Logger.getLogger(PullRequestGHEventSubscriber.class.getName());
    private static final Pattern REPOSITORY_NAME_PATTERN = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+)");

    @Override
    protected boolean isApplicable(@Nullable Item project) {
        return isBuildablePr(project);
    }

    /**
     * @return set with only PULL_REQUEST event
     */
    @Override
    protected Set<GHEvent> events() {
        return immutableEnumSet(PULL_REQUEST);
    }

    @Override
    protected void onEvent(GHSubscriberEvent event) {
        try {
            final GHEventPayload.PullRequest p = GitHub.offline()
                    .parseEventPayload(new StringReader(event.getPayload()), GHEventPayload.PullRequest.class);
            String action = p.getAction();
            String repoUrl = p.getRepository().getHtmlUrl().toExternalForm();
            LOGGER.log(Level.FINE, "Received {0} for {1} from {2}",
                    new Object[]{event.getGHEvent(), repoUrl, event.getOrigin()}
            );
            Matcher matcher = REPOSITORY_NAME_PATTERN.matcher(repoUrl);
            if (matcher.matches()) {
                final GitHubRepositoryName changedRepository = GitHubRepositoryName.create(repoUrl);
                if (changedRepository == null) {
                    LOGGER.log(Level.WARNING, "Malformed repository URL {0}", repoUrl);
                    return;
                }

                BuildablePullRequest buildablePullRequest = new BuildablePullRequest(action, p.getNumber(), p.getPullRequest(), p.getRepository());

                if ("opened".equals(action)) {
                    fireAfterDelay(new BuildablePullRequestSCMHeadEventImpl(
                            SCMEvent.Type.CREATED,
                            event.getTimestamp(),
                            buildablePullRequest,
                            changedRepository,
                            event.getOrigin()
                    ));
                } else if ("reopened".equals(action) || "synchronize".equals(action) || "edited".equals(action)) {
                    fireAfterDelay(new BuildablePullRequestSCMHeadEventImpl(
                            SCMEvent.Type.UPDATED,
                            event.getTimestamp(),
                            buildablePullRequest,
                            changedRepository,
                            event.getOrigin()
                    ));
                } else if ("closed".equals(action)) {
                    fireAfterDelay(new BuildablePullRequestSCMHeadEventImpl(
                            SCMEvent.Type.REMOVED,
                            event.getTimestamp(),
                            buildablePullRequest,
                            changedRepository,
                            event.getOrigin()
                    ));
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

     static class BuildablePullRequest {
        private final String action;
        private final int number;
        private final GHPullRequest pullRequest;
        private final GHRepository repository;

        BuildablePullRequest(String action, int number, GHPullRequest pullRequest, GHRepository repository) {
            this.action = action;
            this.number = number;
            this.pullRequest = pullRequest;
            this.repository = repository;
        }

        public int getNumber() {
            return number;
        }

        public GHPullRequest getPullRequest() {
            return pullRequest;
        }

        public GHRepository getRepository() {
            return repository;
        }

         public String getAction() {
             return action;
         }
     }

}
