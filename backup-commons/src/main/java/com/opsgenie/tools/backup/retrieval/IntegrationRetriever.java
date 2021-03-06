package com.opsgenie.tools.backup.retrieval;

import com.opsgenie.oas.sdk.api.IntegrationActionApi;
import com.opsgenie.oas.sdk.api.IntegrationApi;
import com.opsgenie.oas.sdk.model.ActionCategorized;
import com.opsgenie.oas.sdk.model.Integration;
import com.opsgenie.oas.sdk.model.IntegrationMeta;
import com.opsgenie.oas.sdk.model.ListIntegrationRequest;
import com.opsgenie.tools.backup.dto.IntegrationConfig;
import com.opsgenie.tools.backup.retry.DomainNames;
import com.opsgenie.tools.backup.retry.RateLimitManager;
import com.opsgenie.tools.backup.retry.RetryPolicyAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

public class IntegrationRetriever implements EntityRetriever<IntegrationConfig> {

    private static Logger logger = LoggerFactory.getLogger(IntegrationRetriever.class);

    private static IntegrationApi integrationApi = new IntegrationApi();
    private static IntegrationActionApi integrationActionApi = new IntegrationActionApi();

    private final RateLimitManager rateLimitManager;

    public IntegrationRetriever(RateLimitManager rateLimitManager) {
        this.rateLimitManager = rateLimitManager;
    }


    @Override
    public List<IntegrationConfig> retrieveEntities() throws Exception {
        logger.info("Retrieving current integration configurations");
        final List<IntegrationMeta> integrationMetaList = RetryPolicyAdapter.invoke(new Callable<List<IntegrationMeta>>() {
            @Override
            public List<IntegrationMeta> call() {
                return integrationApi.listIntegrations(new ListIntegrationRequest()).getData();
            }
        });
        final ConcurrentLinkedQueue<IntegrationConfig> integrations = new ConcurrentLinkedQueue<IntegrationConfig>();
        int threadCount = rateLimitManager.getRateLimit(DomainNames.SEARCH, 1);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (final IntegrationMeta meta : integrationMetaList) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        final IntegrationConfig integrationConfig = populateIntegrationActions(meta);
                        sortIntegrationReadOnly(integrationConfig);
                        integrations.add(integrationConfig);
                    } catch (Exception e) {
                        logger.error("Could not retrieve integration with id: " + meta.getId() + " name:" + meta.getName() + "." + e.getMessage());
                    }
                }
            });
        }
        pool.shutdown();
        while (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.info("Retrieving integrations :" + integrations.size() + "/" + integrationMetaList.size());
        }
        return new ArrayList<IntegrationConfig>(integrations);
    }

    private void sortIntegrationReadOnly(IntegrationConfig integrationConfig) {
        Collections.sort(integrationConfig.getIntegration().getReadOnly(), new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return o1.compareToIgnoreCase(o2);
            }
        });
    }

    private IntegrationConfig populateIntegrationActions(final IntegrationMeta meta) throws Exception {
        final IntegrationConfig integrationConfig = new IntegrationConfig();
        final Integration integration = RetryPolicyAdapter.invoke(new Callable<Integration>() {
            @Override
            public Integration call() {
                return integrationApi.getIntegration(meta.getId()).getData();
            }
        });


        integration.setId(meta.getId());
        integrationConfig.setIntegration(integration);
        try {
            integrationConfig.setIntegrationActions(RetryPolicyAdapter.invoke(new Callable<ActionCategorized>() {
                @Override
                public ActionCategorized call() {
                    return integrationActionApi.listIntegrationActions(meta.getId()).getData();
                }
            }));

        } catch (Exception e) {
            logger.info(integration.getName() + " is not an advanced integration, so not exporting actions");
        }
        return integrationConfig;
    }
}
