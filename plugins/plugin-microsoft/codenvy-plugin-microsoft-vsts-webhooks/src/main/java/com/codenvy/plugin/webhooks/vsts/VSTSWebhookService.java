/*
 *  [2012] - [2017] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.plugin.webhooks.vsts;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import com.codenvy.plugin.webhooks.AuthConnection;
import com.codenvy.plugin.webhooks.FactoryConnection;
import com.codenvy.plugin.webhooks.FactoryType;
import com.codenvy.plugin.webhooks.UserConnection;
import com.codenvy.plugin.webhooks.BaseWebhookService;
import com.codenvy.plugin.webhooks.vsts.shared.GenericEvent;
import com.codenvy.plugin.webhooks.vsts.shared.PullRequestUpdatedEvent;
import com.codenvy.plugin.webhooks.vsts.shared.WorkItemCreationEvent;
import com.codenvy.plugin.webhooks.vsts.shared.WorkItemCreationResource;

import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.rest.shared.dto.Link;
import org.eclipse.che.api.factory.shared.dto.FactoryDto;
import org.eclipse.che.api.factory.shared.dto.PoliciesDto;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.lang.IoUtil;
import org.eclipse.che.commons.lang.Pair;
import org.eclipse.che.dto.server.DtoFactory;
import org.eclipse.che.inject.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.stream.Collectors.toSet;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static com.codenvy.plugin.webhooks.FactoryType.DEVELOP;
import static com.codenvy.plugin.webhooks.FactoryType.REVIEW;
import static com.codenvy.plugin.webhooks.vsts.VSTSWebhookType.PULL_REQUEST_UPDATED_WEBHOOK;
import static com.codenvy.plugin.webhooks.vsts.VSTSWebhookType.WORK_ITEM_CREATED_WEBHOOK;

@Api(
        value = "/vsts-webhook",
        description = "VSTS webhooks handler"
)
@Path("/vsts-webhook")
public class VSTSWebhookService extends BaseWebhookService {

    private static final Logger LOG                               = LoggerFactory.getLogger(VSTSWebhookService.class);
    private static final String VSTS_WEBHOOKS_PROPERTIES_FILENAME = "vsts-webhooks.properties";
    private static final String WEBHOOK_PROPERTY_PATTERN          = "env.CODENVY_VSTS_WEBHOOK_.+";
    private static final String WEBHOOK_TYPE_SUFFIX               = "_TYPE";
    private static final String WEBHOOK_HOST_SUFFIX               = "_HOST";
    private static final String WEBHOOK_ACCOUNT_SUFFIX            = "_ACCOUNT";
    private static final String WEBHOOK_COLLECTION_SUFFIX         = "_COLLECTION";
    private static final String WEBHOOK_API_VERSION_SUFFIX        = "_API_VERSION";
    private static final String WEBHOOK_USERNAME_SUFFIX           = "_USERNAME";
    private static final String WEBHOOK_PASSWORD_SUFFIX           = "_PASSWORD";
    private static final String WEBHOOK_FACTORY_ID_SUFFIX_PATTERN = "_FACTORY.+_ID";

    private final FactoryConnection       factoryConnection;
    private final UserConnection          userConnection;
    private final VSTSConnection          vstsConnection;
    private final ConfigurationProperties configurationProperties;

    @Inject
    public VSTSWebhookService(final AuthConnection authConnection,
                              final FactoryConnection factoryConnection,
                              final UserConnection userConnection,
                              final VSTSConnection vstsConnection,
                              final ConfigurationProperties configurationProperties,
                              @Named("integration.factory.owner.username") String username,
                              @Named("integration.factory.owner.password") String password) {
        super(authConnection, factoryConnection, configurationProperties, username, password);

        this.factoryConnection = factoryConnection;
        this.userConnection = userConnection;
        this.vstsConnection = vstsConnection;
        this.configurationProperties = configurationProperties;
    }

    @ApiOperation(value = "Handle VSTS webhook events",
                  response = Response.class)
    @ApiResponses({
                          @ApiResponse(code = 200, message = "OK"),
                          @ApiResponse(code = 202, message = "The request has been accepted for processing, but the processing has not been completed."),
                          @ApiResponse(code = 500, message = "Internal Server Error")
                  })
    @POST
    @Consumes(APPLICATION_JSON)
    public Response handleVSTSWebhookEvent(@ApiParam(value = "New VSTS event", required = true)
                                           @Context HttpServletRequest request)
            throws ServerException {
        Response response = Response.ok().build();
        try (ServletInputStream inputStream = request.getInputStream()) {
            final String requestInputString = IoUtil.readAndCloseQuietly(inputStream);

            // Create {@link GenericEvent} from JSON
            final GenericEvent genericEvent = DtoFactory.getInstance().createDtoFromJson(requestInputString, GenericEvent.class);
            final String eventType = genericEvent.getEventType();
            if (!isNullOrEmpty(eventType)) {
                switch (eventType) {
                    case "workitem.created":
                        // Create {@link WorkItemCreationEvent} from JSON
                        final WorkItemCreationEvent wicEvent =
                                DtoFactory.getInstance().createDtoFromJson(requestInputString, WorkItemCreationEvent.class);
                        handleWorkItemCreationEvent(wicEvent);
                        break;
                    case "git.pullrequest.updated":
                        // Create {@link PullRequestUpdatedEvent} from JSON
                        final PullRequestUpdatedEvent pruEvent =
                                DtoFactory.getInstance().createDtoFromJson(requestInputString, PullRequestUpdatedEvent.class);
                        handlePullRequestUpdatedEvent(pruEvent);
                        break;
                    default:
                        response = Response.accepted(
                                new GenericEntity<>("VSTS message \'" + eventType + "\' received. It isn't intended to be processed.",
                                                    String.class)).build();
                        break;
                }
            }

        } catch (IOException e) {
            LOG.error(e.getLocalizedMessage());
            throw new ServerException(e.getLocalizedMessage());
        }
        return response;
    }

    /**
     * Handle VSTS {@link WorkItemCreationEvent}
     *
     * @param workItemCreationEvent
     *         the work item creation event to handle
     * @return HTTP 200 response if event was processed successfully
     * HTTP 202 response if event was processed partially
     * @throws ServerException
     */
    private void handleWorkItemCreationEvent(WorkItemCreationEvent workItemCreationEvent) throws ServerException {
        LOG.debug("{}", workItemCreationEvent);

        // Set current Codenvy user
        if (EnvironmentContext.getCurrent().getSubject().isAnonymous()) {
            EnvironmentContext.getCurrent().setSubject(new TokenSubject());
        }

        // Get current user id
        final String userId = userConnection.getCurrentUser().getId();

        // Get work item data
        final WorkItemCreationResource resource = workItemCreationEvent.getResource();
        final String projectName = resource.getFields().getTeamProject().toLowerCase(Locale.getDefault());
        final String workItemId = resource.getId();
        final String workItemUrl = resource.getLinks().getSelf().getHref();

        // Get parent factory for the project
        final List<FactoryDto> parentFactories = factoryConnection.findFactory(projectName, userId);

        if (parentFactories.isEmpty()) {
            LOG.error("No parent factory with name {} found", projectName);
            throw new ServerException("No parent factory with name " + projectName + " found");
        }

        final FactoryDto parentFactory = parentFactories.get(0);

        // Create Develop & Review factories from parent factory
        final FactoryDto developFactory = createFactoryForWorkItem(parentFactory, DEVELOP, workItemId);
        final FactoryDto storedDevelopFactory = factoryConnection.saveFactory(developFactory);
        LOG.debug("storedDevelopFactory: {}", storedDevelopFactory);
        final FactoryDto reviewFactory = createFactoryForWorkItem(parentFactory, REVIEW, workItemId);
        final FactoryDto storedReviewFactory = factoryConnection.saveFactory(reviewFactory);
        LOG.debug("storedReviewFactory: {}", storedReviewFactory);

        // Get VSTS data from work item URL
        // URL to parse: 'https://{account}.{host}.com/{collection}/_apis/wit/workItems'
        final String collectionUrl = workItemUrl.substring(0, workItemUrl.indexOf("/_apis/wit/workItems"));
        LOG.debug("collectionUrl: {}", collectionUrl);
        final String[] collectionUrlSplit = collectionUrl.split("/");
        final String collection = collectionUrlSplit[collectionUrlSplit.length - 1];

        final String[] hostSplit = collectionUrlSplit[2].split("\\.");
        final String account = hostSplit[0];
        final String host = hostSplit[1];

        // Get configured 'work item created' webhook for given VSTS account, host and collection
        Optional<WorkItemCreatedWebhook> webhook = getWorkItemCreatedWebhook(host, account, collection);

        WorkItemCreatedWebhook w = webhook.orElseThrow(
                () -> new ServerException("No 'work item created' webhook configured for collection URL " + collectionUrl));

        // Prepare data to store in VSTS project settings
        final String apiVersion = w.getApiVersion();
        final Pair<String, String> credentials = w.getCredentials();

        final String developSettingKey = String.format("WI%s-%s-factory", workItemId, DEVELOP.toString());
        final String reviewSettingKey = String.format("WI%s-%s-factory", workItemId, REVIEW.toString());

        final String developFactoryUrl = getFactoryUrl(storedDevelopFactory);
        final String reviewFactoryUrl = getFactoryUrl(storedReviewFactory);

        // Push factory URLs to VSTS project settings storage
        vstsConnection.storeFactorySetting(host, account, collection, apiVersion, credentials, developSettingKey, developFactoryUrl);
        vstsConnection.storeFactorySetting(host, account, collection, apiVersion, credentials, reviewSettingKey, reviewFactoryUrl);

        // Create/update 'pull request updated' webhook that contains Develop & Review factories
        final Optional<PullRequestUpdatedWebhook> pruWebhook = getPullRequestUpdatedWebhook(host, account, collection);
        if (pruWebhook.isPresent()) {
            final PullRequestUpdatedWebhook pruW = pruWebhook.get();
            pruW.addFactoryId(storedDevelopFactory.getId());
            pruW.addFactoryId(storedReviewFactory.getId());
            storePullRequestUpdatedWebhook(pruW);
        } else {
            storePullRequestUpdatedWebhook(
                    new PullRequestUpdatedWebhook(host, account, collection, apiVersion, credentials, storedDevelopFactory.getId(),
                                                  storedReviewFactory.getId()));
        }
    }

    /**
     * Handle VSTS {@link PullRequestUpdatedEvent}
     *
     * @param pullRequestUpdatedEvent
     *         the pull request creation event to handle
     * @return HTTP 200 response if event was processed successfully
     * HTTP 202 response if event was processed partially
     * @throws ServerException
     */
    private void handlePullRequestUpdatedEvent(PullRequestUpdatedEvent pullRequestUpdatedEvent) throws ServerException {
        LOG.debug("{}", pullRequestUpdatedEvent);

        // Set current Codenvy user
        if (EnvironmentContext.getCurrent().getSubject().isAnonymous()) {
            EnvironmentContext.getCurrent().setSubject(new TokenSubject());
        }

        // Get event data
        final String prStatus = pullRequestUpdatedEvent.getResource().getStatus();
        final String prMergeStatus = pullRequestUpdatedEvent.getResource().getMergeStatus();

        // Check that PR is completed and commits merged
        if ("completed".equals(prStatus) && "succeeded".equals(prMergeStatus)) {

            // Get event source data
            final String repositoryIdUrl = pullRequestUpdatedEvent.getResource().getRepository().getUrl();

            final String[] sourceBranchSplit = pullRequestUpdatedEvent.getResource().getSourceRefName().split("/");
            final String sourceBranch = sourceBranchSplit[sourceBranchSplit.length - 1];

            final String headCommitId = pullRequestUpdatedEvent.getResource().getLastMergeSourceCommit().getCommitId();

            // Get VSTS data from repository URL
            // URL to parse: 'https://{account}.{host}.com/{collection}/_apis/git/repositories/{repositoryId}'
            final String collectionUrl = repositoryIdUrl.split("/_apis/git/repositories/")[0];
            LOG.debug("collectionUrl: {}", collectionUrl);
            final String[] collectionUrlSplit = collectionUrl.split("/");
            final String collection = collectionUrlSplit[collectionUrlSplit.length - 1];

            final String[] hostSplit = collectionUrlSplit[2].split("\\.");
            final String account = hostSplit[0];
            final String host = hostSplit[1];

            // Get VSTS 'pull request merged' webhook configured for given host, account and collection
            final Optional<PullRequestUpdatedWebhook> webhook = getPullRequestUpdatedWebhook(host, account, collection);

            final PullRequestUpdatedWebhook w = webhook.orElseThrow(() -> new ServerException(
                    "No 'pull request updated' webhook configured for host " + host + ", account " + account + " and collection " +
                    collection));

            // Get factory id's listed into the webhook
            final Set<String> factoryIDs = w.getFactoriesIds();

            // Get repository named URL
            final String apiVersion = w.getApiVersion();
            final Pair<String, String> credentials = w.getCredentials();
            final String repositoryNameUrl = vstsConnection.getRepositoryNameUrl(repositoryIdUrl, apiVersion, credentials);

            // Get factories that contain a project for given repository and branch
            final List<FactoryDto> factories = getFactoriesForRepositoryAndBranch(factoryIDs, repositoryNameUrl, sourceBranch);
            if (factories.isEmpty()) {
                LOG.error("No factory found for branch {}", sourceBranch);
                throw new ServerException("No factory found for branch " + sourceBranch);
            }

            for (FactoryDto f : factories) {
                // Update project into the factory with given repository and branch
                final FactoryDto updatedfactory =
                        updateProjectInFactory(f, repositoryNameUrl, sourceBranch, headCommitId);

                // Persist updated factory
                updateFactory(updatedfactory);

                // Remove factory id from webhook
                w.removeFactoryId(f.getId());
            }

            // Update 'pull request merged' webhook configured in properties file
            storePullRequestUpdatedWebhook(w);
        }
    }

    /**
     * Create a new factory for a VSTS work item based on the Team Project parent factory and save it
     *
     * @param parentFactory
     *         the parent factory to base the new factory on
     * @param factoryType
     *         'DEVELOP' or 'REVIEW'
     * @param workItemId
     *         the id of the VSTS work item
     * @return the new created factory
     * @throws ServerException
     */
    private FactoryDto createFactoryForWorkItem(final FactoryDto parentFactory, final FactoryType factoryType, final String workItemId)
            throws ServerException {
        final FactoryDto newFactory = DtoFactory.cloneDto(parentFactory);

        final String createPolicy = ((DEVELOP == factoryType) ? "perUser" : "perClick");
        PoliciesDto policies;
        if (parentFactory.getPolicies() == null) {
            policies = DtoFactory.newDto(PoliciesDto.class).withCreate(createPolicy);
        } else {
            policies = parentFactory.getPolicies().withCreate(createPolicy);
        }
        newFactory.setPolicies(policies);

        final String projectName = parentFactory.getName();
        newFactory.setName(projectName + "-" + workItemId + "-" + factoryType.toString() + "-factory");
        newFactory.setCreator(null);
        newFactory.setId(null);

        Map<String, String> projectSourceParameters = newFactory.getWorkspace().getProjects().get(0).getSource().getParameters();
        projectSourceParameters.put("branch", projectName + "-" + workItemId);

        return newFactory;
    }

    /**
     * Get factory URL
     *
     * @param factory
     *         the factory to get link from
     * @return the factory 'open factory' URL
     */
    private String getFactoryUrl(final FactoryDto factory) throws ServerException {
        final Link factoryLink = factory.getLink(FACTORY_URL_REL);
        if (factoryLink == null) {
            throw new ServerException("Factory " + factory.getName() + " do not contain mandatory \'" + FACTORY_URL_REL + "\' link");
        }
        return factoryLink.getHref();
    }

    /**
     * Get configured 'work item created' webhook for given account, host and collection
     *
     * @param host
     *         the VSTS host
     * @param account
     *         the VSTS account
     * @param collection
     *         the VSTS collection
     * @return the webhook configured for given account, host and collection or null if no webhook is configured
     * @throws ServerException
     */
    private Optional<WorkItemCreatedWebhook> getWorkItemCreatedWebhook(final String host, final String account, final String collection)
            throws ServerException {
        final List webhooks = getVSTSWebhooks(WORK_ITEM_CREATED_WEBHOOK);
        WorkItemCreatedWebhook webhook = null;
        for (Object o : webhooks) {
            final WorkItemCreatedWebhook w = (WorkItemCreatedWebhook)o;
            final String webhookHost = w.getHost();
            final String webhookAccount = w.getAccount();
            final String webhookCollection = w.getCollection();
            if (host.equals(webhookHost) && account.equals(webhookAccount) && collection.equals(webhookCollection)) {
                webhook = w;
                break;
            }
        }
        return Optional.ofNullable(webhook);
    }

    /**
     * Get configured 'pull request updated' webhook for given account, host and collection
     *
     * @param host
     *         the VSTS host
     * @param account
     *         the VSTS account
     * @param collection
     *         the VSTS collection
     * @return the webhook configured for given account, host and collection or null if no webhook is configured
     * @throws ServerException
     */
    private Optional<PullRequestUpdatedWebhook> getPullRequestUpdatedWebhook(final String host, final String account,
                                                                             final String collection)
            throws ServerException {
        final List webhooks = getVSTSWebhooks(PULL_REQUEST_UPDATED_WEBHOOK);
        PullRequestUpdatedWebhook webhook = null;
        for (Object o : webhooks) {
            final PullRequestUpdatedWebhook w = (PullRequestUpdatedWebhook)o;
            final String webhookHost = w.getHost();
            final String webhookAccount = w.getAccount();
            final String webhookCollection = w.getCollection();
            if (host.equals(webhookHost) && account.equals(webhookAccount) && collection.equals(webhookCollection)) {
                webhook = w;
                break;
            }
        }
        return Optional.ofNullable(webhook);
    }

    /**
     * Get configured VSTS webhooks of given type
     *
     * @param webhookType
     *         WORK_ITEM_CREATED_WEBHOOK or PULL_REQUEST_UPDATED_WEBHOOK
     * @return the list of webhooks of given type contained in VSTS_WEBHOOKS_PROPERTIES_FILENAME properties file
     */
    private List getVSTSWebhooks(final VSTSWebhookType webhookType) throws ServerException {
        Map<String, String> properties = configurationProperties.getProperties(WEBHOOK_PROPERTY_PATTERN);

        Set<String> webhooks = properties.entrySet()
                                         .stream()
                                         .filter(entry -> webhookType.toString().equals(entry.getValue()))
                                         .map(entry -> entry.getKey()
                                                            .substring(0, entry.getKey().lastIndexOf(WEBHOOK_TYPE_SUFFIX)))
                                         .collect(toSet());

        if (webhooks.isEmpty()) {
            LOG.error("No VSTS webhooks with '{}' type were registered", webhookType);
        }

        if (webhookType == WORK_ITEM_CREATED_WEBHOOK) {
            List<WorkItemCreatedWebhook> wicWebhooks = new ArrayList<>();
            for (String webhook : webhooks) {
                WorkItemCreatedWebhook wicWebhook = new WorkItemCreatedWebhook(properties.get(webhook + WEBHOOK_HOST_SUFFIX),
                                                                               properties.get(webhook + WEBHOOK_ACCOUNT_SUFFIX),
                                                                               properties.get(webhook + WEBHOOK_COLLECTION_SUFFIX),
                                                                               properties.get(webhook + WEBHOOK_API_VERSION_SUFFIX),
                                                                               Pair.of(properties.get(webhook + WEBHOOK_USERNAME_SUFFIX),
                                                                                       properties.get(webhook + WEBHOOK_PASSWORD_SUFFIX)));
                wicWebhooks.add(wicWebhook);
                LOG.debug("new WorkItemCreatedWebhook({})", wicWebhook);
            }
            return wicWebhooks;
        } else if (webhookType == PULL_REQUEST_UPDATED_WEBHOOK) {
            List<PullRequestUpdatedWebhook> pruWebhooks = new ArrayList<>();
            for (String webhook : webhooks) {
                String[] factoriesIDs = properties.entrySet()
                                                  .stream()
                                                  .filter(entry -> webhooks.stream().anyMatch(
                                                          w -> entry.getKey().matches(w + WEBHOOK_FACTORY_ID_SUFFIX_PATTERN)))
                                                  .map(Entry::getValue)
                                                  .toArray(String[]::new);
                PullRequestUpdatedWebhook wicWebhook = new PullRequestUpdatedWebhook(properties.get(webhook + WEBHOOK_HOST_SUFFIX),
                                                                                     properties.get(webhook + WEBHOOK_ACCOUNT_SUFFIX),
                                                                                     properties.get(webhook + WEBHOOK_COLLECTION_SUFFIX),
                                                                                     properties.get(webhook + WEBHOOK_API_VERSION_SUFFIX),
                                                                                     Pair.of(properties.get(webhook +
                                                                                                            WEBHOOK_USERNAME_SUFFIX),
                                                                                             properties.get(webhook +
                                                                                                            WEBHOOK_PASSWORD_SUFFIX)),
                                                                                     factoriesIDs);
                pruWebhooks.add(wicWebhook);
                LOG.debug("new WorkItemCreatedWebhook({})", wicWebhook);
            }
            return pruWebhooks;
        }
        return new ArrayList();
    }

    /**
     * Store a 'pull request updated' webhook in webhooks property file.
     * If a webhook with same id already exist it will be replaced.
     *
     * @param pruWebhook
     *         the webhook to store in webhooks property file
     * @throws ServerException
     */
    private void storePullRequestUpdatedWebhook(final PullRequestUpdatedWebhook pruWebhook) throws ServerException {
        final Set<String> factoriesIDs = pruWebhook.getFactoriesIds();
        String propertyValue = String.format("%s,%s,%s,%s,%s,%s,%s",
                                             PULL_REQUEST_UPDATED_WEBHOOK.toString(),
                                             pruWebhook.getHost(),
                                             pruWebhook.getAccount(),
                                             pruWebhook.getCollection(),
                                             pruWebhook.getApiVersion(),
                                             pruWebhook.getCredentials().first,
                                             pruWebhook.getCredentials().second);

        if (factoriesIDs.size() > 0) {
            final String concatedFactoriesIDs = String.join(";", factoriesIDs);
            propertyValue = propertyValue + "," + concatedFactoriesIDs;
        }

        storeProperty(pruWebhook.getId(), propertyValue, VSTS_WEBHOOKS_PROPERTIES_FILENAME);
    }
}
