package com.capitalone.dashboard.event.sync;

import com.capitalone.dashboard.model.BaseModel;
import com.capitalone.dashboard.model.Build;
import com.capitalone.dashboard.model.CodeQuality;
import com.capitalone.dashboard.model.Collector;
import com.capitalone.dashboard.model.CollectorItem;
import com.capitalone.dashboard.model.CollectorType;
import com.capitalone.dashboard.model.Component;
import com.capitalone.dashboard.model.Dashboard;
import com.capitalone.dashboard.model.RepoBranch;
import com.capitalone.dashboard.model.StandardWidget;
import com.capitalone.dashboard.model.Widget;
import com.capitalone.dashboard.model.relation.RelatedCollectorItem;
import com.capitalone.dashboard.repository.BuildRepository;
import com.capitalone.dashboard.repository.CodeQualityRepository;
import com.capitalone.dashboard.repository.CollectorItemRepository;
import com.capitalone.dashboard.repository.CollectorRepository;
import com.capitalone.dashboard.repository.ComponentRepository;
import com.capitalone.dashboard.repository.DashboardRepository;
import com.capitalone.dashboard.repository.RelatedCollectorItemRepository;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.collections4.CollectionUtils;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@org.springframework.stereotype.Component
public class SyncDashboard {
    private final DashboardRepository dashboardRepository;
    private final ComponentRepository componentRepository;
    private final CollectorRepository collectorRepository;
    private final CollectorItemRepository collectorItemRepository;
    private final BuildRepository buildRepository;
    private final RelatedCollectorItemRepository relatedCollectorItemRepository;
    private final CodeQualityRepository codeQualityRepository;

    private static final String BUILD_REPO_REASON = "Code Repo build";
    private static final String CODEQUALITY_TRIGGERED_REASON = "Code scan triggered by build";

    @Autowired
    public SyncDashboard(DashboardRepository dashboardRepository, ComponentRepository componentRepository,
                         CollectorRepository collectorRepository, CollectorItemRepository collectorItemRepository,
                         BuildRepository buildRepository, RelatedCollectorItemRepository relatedCollectorItemRepository,
                         CodeQualityRepository codeQualityRepository) {
        this.dashboardRepository = dashboardRepository;
        this.componentRepository = componentRepository;
        this.collectorRepository = collectorRepository;
        this.collectorItemRepository = collectorItemRepository;
        this.buildRepository = buildRepository;
        this.relatedCollectorItemRepository = relatedCollectorItemRepository;
        this.codeQualityRepository = codeQualityRepository;
    }


    /**
     * Get the widget by name from a dashboard
     *
     * @param name
     * @param dashboard
     * @return widget
     */
    public Widget getWidget(String name, Dashboard dashboard) {
        List<Widget> widgets = dashboard.getWidgets();
        return widgets.stream().filter(widget -> widget.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /**
     * @param existingDashboards List of dashboards to which the item should be added
     * @param collectorItem      The collector item to add
     * @param collectorType      The collector type of the item
     * @param addWidget          add a corresponding widget or not
     */
    private void addCollectorItemToDashboard(List<Dashboard> existingDashboards, CollectorItem collectorItem, CollectorType collectorType, boolean addWidget) {
        if (CollectionUtils.isEmpty(existingDashboards)) return;
        /**
         * The assumption is the dashboard already has a SCM widget and the sync process should not add SCM widgets.
         */
        if(CollectorType.SCM.equals(collectorType)) return;

        for(Dashboard dashboard : existingDashboards) {
            ObjectId componentId = dashboard.getWidgets().get(0).getComponentId();
            StandardWidget standardWidget = new StandardWidget(collectorType, componentId);
            Component component = componentRepository.findOne(componentId);
            if (component == null) continue;

            // if the additional association logic does not comply do not associate
            if(!associateByType(collectorType, component, collectorItem)) continue;

            component.addCollectorItem(collectorType, collectorItem);
            componentRepository.save(component);
            collectorItem.setEnabled(true);
            collectorItemRepository.save(collectorItem);

            if (addWidget && (getWidget(standardWidget.getName(), dashboard) == null)) {
                Widget widget = standardWidget.getWidget();
                dashboard.getWidgets().add(widget);
                dashboardRepository.save(dashboard);
            }

        }
    }

    /*
    * Additional logic for association by Collector Type
    * */
    private boolean associateByType(CollectorType collectorType, Component component, CollectorItem collectorItem) {
        switch (collectorType) {
            case Build: return associateBuild(component,collectorItem);
            case CodeQuality: return associateCodeQuality(component,collectorItem);
            default: return true;
        }
    }

    /*
    * Association logic for Build Collector Type
    * */
    private boolean associateBuild(Component component, CollectorItem collectorItem) {
        List<CollectorItem> scmCollectorItems = Lists.newArrayList(component.getCollectorItems(CollectorType.SCM));
        if(CollectionUtils.isEmpty(scmCollectorItems)) return false;

        // get the last build and compare the codeRepos to be subset of validRepoBranchSet if not then do not associate dashboard.
        Build build = buildRepository.findTop1ByCollectorItemIdOrderByTimestampDesc(collectorItem.getId());
        if(build == null) return false;

        Set<RepoBranch> validRepoBranchSet = buildValidRepoBranches(component, getRepoType(build));
        Set<RepoBranch> repoBranchesBuild = Sets.newConcurrentHashSet(build.getCodeRepos());

        return CollectionUtils.isSubCollection(repoBranchesBuild, validRepoBranchSet);
    }

    /*
     * Association logic for CodeQuality Collector Type
     * */
    private boolean associateCodeQuality (@NotNull Component component,@NotNull CollectorItem collectorItem) {
        List<CollectorItem> scmCollectorItems = Lists.newArrayList(component.getCollectorItems(CollectorType.SCM));
        if(CollectionUtils.isEmpty(scmCollectorItems)) return false;

        CodeQuality entity = codeQualityRepository.findTop1ByCollectorItemIdOrderByTimestampDesc(collectorItem.getId());
        if(entity == null || entity.getBuildId() == null) return false;

        Build build = buildRepository.findOne(entity.getBuildId());
        if(build == null) return false;

        Set<RepoBranch> validRepoBranchSet = buildValidRepoBranches(component, getRepoType(build));
        Set<RepoBranch> repoBranchesBuild = Sets.newConcurrentHashSet(build.getCodeRepos());

        return CollectionUtils.isSubCollection(repoBranchesBuild, validRepoBranchSet);
    }

    /*
     * Build a unique set of Valid RepoBranches by checking all SCM CollectorItems.
     * */
    private Set<RepoBranch> buildValidRepoBranches (@NotNull Component component, @NotNull RepoBranch.RepoType repoType) {
        List<CollectorItem> scmCollectorItems = Lists.newArrayList(component.getCollectorItems(CollectorType.SCM));
        return Sets.newConcurrentHashSet(scmCollectorItems.stream()
                .map(item -> new RepoBranch(String.valueOf(item.getOptions().get("url")),
                        String.valueOf(item.getOptions().get("branch")),
                        repoType)).collect(Collectors.toSet()));
    }

    /*
    * Retrieve Repotype from Build.
    * */
    private RepoBranch.RepoType getRepoType(@NotNull Build build) {
        if(CollectionUtils.isEmpty(build.getCodeRepos())) return RepoBranch.RepoType.Unknown;
        return build.getCodeRepos().get(0).getType();
    }

    /**
     * Get all the dashboards that have the collector items
     *
     * @param collectorItems collector items
     * @param collectorType  type of the collector
     * @return a list of dashboards
     */
    public List<Dashboard> getDashboardsByCollectorItems(Set<CollectorItem> collectorItems, CollectorType collectorType) {
        if (CollectionUtils.isEmpty(collectorItems)) {
            return new ArrayList<>();
        }
        List<ObjectId> collectorItemIds = collectorItems.stream().map(BaseModel::getId).collect(Collectors.toList());
        // Find the components that have these collector items
        List<com.capitalone.dashboard.model.Component> components = componentRepository.findByCollectorTypeAndItemIdIn(collectorType, collectorItemIds);
        List<ObjectId> componentIds = components.stream().map(BaseModel::getId).collect(Collectors.toList());
        return dashboardRepository.findByApplicationComponentIdsIn(componentIds);
    }


    /**
     * Sync builds with dashboards
     *
     * @param build
     */
    public void sync(Build build) {

        /** Step 1: Add build collector item to Dashboard if built repo is in on the dashboard. **/

        // Find the collectorItem of build
        CollectorItem buildCollectorItem = collectorItemRepository.findOne(build.getCollectorItemId());

        //Find possible collectors and then the collector ids for SCM
        List<Collector> scmCollectors = collectorRepository.findAllByCollectorType(CollectorType.SCM);
        if (CollectionUtils.isEmpty(scmCollectors)) {
            return;
        }
        List<ObjectId> scmCollectorIds = scmCollectors.stream().map(BaseModel::getId).collect(Collectors.toList());

        // Get the repos that are being built
        List<RepoBranch> repos = build.getCodeRepos();
        Set<CollectorItem> repoCollectorItemsInBuild = new HashSet<>();

        //create a list of the repo collector items that are being built, most cases have only 1
        repos.stream().map(repoBranch -> collectorItemRepository.findAllByOptionNameValueAndCollectorIdsIn("url", repoBranch.getUrl(), scmCollectorIds))
                .forEach(collectorItems -> CollectionUtils.addAll(repoCollectorItemsInBuild, collectorItems.iterator()));

        // For each repo collector item, add the item to the referenced dashboards
        repoCollectorItemsInBuild.forEach(
                ci -> {
                    relatedCollectorItemRepository.saveRelatedItems(buildCollectorItem.getId(), ci.getId(), this.getClass().toString(),  BUILD_REPO_REASON);
                }
        );
    }

    /**
     * Sync code quality with dashboards
     *
     * @param codeQuality
     */
    public void sync(CodeQuality codeQuality) {
        ObjectId buildId = codeQuality.getBuildId();
        if (buildId == null) return;
        Build build = buildRepository.findOne(buildId);
        if (build == null) return;
        relatedCollectorItemRepository.saveRelatedItems(build.getCollectorItemId(), codeQuality.getCollectorItemId(), this.getClass().toString(), CODEQUALITY_TRIGGERED_REASON);
    }


    /**
     * Sync up dashboards based on related collector item
     *
     * @param relatedCollectorItem
     * @throws SyncException
     */
    public void sync(RelatedCollectorItem relatedCollectorItem) throws SyncException{
        ObjectId left = relatedCollectorItem.getLeft();
        ObjectId right = relatedCollectorItem.getRight();
        CollectorItem leftItem = collectorItemRepository.findOne(left);
        CollectorItem rightItem = collectorItemRepository.findOne(right);

        if (leftItem == null) throw new SyncException("Missing left collector item");
        if (rightItem == null) throw new SyncException("Missing right collector item");

        Collector leftCollector = collectorRepository.findOne(leftItem.getCollectorId());
        Collector rightCollector = collectorRepository.findOne(rightItem.getCollectorId());

        if (leftCollector == null) throw new SyncException("Missing left collector");
        if (rightCollector == null) throw new SyncException("Missing right collector");

        List<Dashboard> dashboardsWithLeft = getDashboardsByCollectorItems(Sets.newHashSet(leftItem), leftCollector.getCollectorType());
        List<Dashboard> dashboardsWithRight = getDashboardsByCollectorItems(Sets.newHashSet(rightItem), rightCollector.getCollectorType());

        addCollectorItemToDashboard(dashboardsWithLeft, rightItem, rightCollector.getCollectorType(), true);
        addCollectorItemToDashboard(dashboardsWithRight, leftItem, leftCollector.getCollectorType(), true);
    }

}
