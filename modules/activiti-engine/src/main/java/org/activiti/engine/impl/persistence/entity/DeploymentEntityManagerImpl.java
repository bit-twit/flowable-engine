/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.engine.impl.persistence.entity;

import java.util.List;
import java.util.Map;

import org.activiti.bpmn.model.EventDefinition;
import org.activiti.bpmn.model.StartEvent;
import org.activiti.bpmn.model.TimerEventDefinition;
import org.activiti.engine.ProcessEngineConfiguration;
import org.activiti.engine.delegate.event.ActivitiEventType;
import org.activiti.engine.delegate.event.impl.ActivitiEventBuilder;
import org.activiti.engine.impl.DeploymentQueryImpl;
import org.activiti.engine.impl.Page;
import org.activiti.engine.impl.ProcessDefinitionQueryImpl;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.event.MessageEventHandler;
import org.activiti.engine.impl.jobexecutor.TimerEventHandler;
import org.activiti.engine.impl.jobexecutor.TimerStartEventJobHandler;
import org.activiti.engine.impl.util.ProcessDefinitionUtil;
import org.activiti.engine.impl.util.TimerUtil;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.runtime.Job;
import org.apache.commons.collections.CollectionUtils;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public class DeploymentEntityManagerImpl extends AbstractEntityManager<DeploymentEntity> implements DeploymentEntityManager {

  @Override
  public void insertDeployment(DeploymentEntity deployment) {
    getDbSqlSession().insert(deployment);

    for (ResourceEntity resource : deployment.getResources().values()) {
      resource.setDeploymentId(deployment.getId());
      getResourceManager().insert(resource);
    }
  }

  @Override
  public void deleteDeployment(String deploymentId, boolean cascade) {
    List<ProcessDefinition> processDefinitions = getDbSqlSession().createProcessDefinitionQuery().deploymentId(deploymentId).list();

    // Remove the deployment link from any model.
    // The model will still exists, as a model is a source for a deployment
    // model and has a different lifecycle
    List<Model> models = getDbSqlSession().createModelQueryImpl().deploymentId(deploymentId).list();
    for (Model model : models) {
      ModelEntity modelEntity = (ModelEntity) model;
      modelEntity.setDeploymentId(null);
      getModelManager().updateModel(modelEntity);
    }

    if (cascade) {

      // delete process instances
      for (ProcessDefinition processDefinition : processDefinitions) {
        String processDefinitionId = processDefinition.getId();

        getProcessInstanceManager().deleteProcessInstancesByProcessDefinition(processDefinitionId, "deleted deployment", cascade);

      }
    }

    for (ProcessDefinition processDefinition : processDefinitions) {
      String processDefinitionId = processDefinition.getId();
      // remove related authorization parameters in IdentityLink table
      getIdentityLinkManager().deleteIdentityLinksByProcDef(processDefinitionId);

      // event subscriptions
      getEventSubscriptionManager().deleteEventSubscriptionsForProcessDefinition(processDefinitionId);
    }

    // delete process definitions from db
    getProcessDefinitionManager().deleteProcessDefinitionsByDeploymentId(deploymentId);

    for (ProcessDefinition processDefinition : processDefinitions) {

      // remove timer start events for current process definition:
      
      List<Job> timerStartJobs = Context.getCommandContext().getJobEntityManager()
          .findJobsByTypeAndProcessDefinitionId(TimerStartEventJobHandler.TYPE, processDefinition.getId());
      if (timerStartJobs != null && timerStartJobs.size() > 0) {
        for (Job timerStartJob : timerStartJobs) {
          if (Context.getProcessEngineConfiguration().getEventDispatcher().isEnabled()) {
            Context.getProcessEngineConfiguration()
                   .getEventDispatcher()
                   .dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.JOB_CANCELED, timerStartJob, null, null, processDefinition.getId()));
          }

          Context.getCommandContext().getJobEntityManager().delete((JobEntity) timerStartJob);
        }
      }
      
      // If previous process definition version has a timer start event, it must be added
      ProcessDefinitionEntity latestProcessDefinition = null;
      if (processDefinition.getTenantId() != null && !ProcessEngineConfiguration.NO_TENANT_ID.equals(processDefinition.getTenantId())) {
        latestProcessDefinition = Context.getCommandContext().getProcessDefinitionEntityManager()
            .findLatestProcessDefinitionByKeyAndTenantId(processDefinition.getKey(), processDefinition.getTenantId());
      } else {
        latestProcessDefinition = Context.getCommandContext().getProcessDefinitionEntityManager()
            .findLatestProcessDefinitionByKey(processDefinition.getKey());
      }

      // Only if the currently deleted process definition is the latest version, we fall back to the previous timer start event
      if (processDefinition.getId().equals(latestProcessDefinition.getId())) { 
        
        // Try to find a previous version (it could be some versions are missing due to deletions)
        int previousVersion = processDefinition.getVersion() - 1;
        ProcessDefinitionEntity previousProcessDefinition = null;
        while (previousProcessDefinition == null && previousVersion > 0) {
          
          ProcessDefinitionQueryImpl previousProcessDefinitionQuery = new ProcessDefinitionQueryImpl(Context.getCommandContext())
            .processDefinitionVersion(previousVersion)
            .processDefinitionKey(processDefinition.getKey());
        
          if (processDefinition.getTenantId() != null && !ProcessEngineConfiguration.NO_TENANT_ID.equals(processDefinition.getTenantId())) {
            previousProcessDefinitionQuery.processDefinitionTenantId(processDefinition.getTenantId());
          } else {
            previousProcessDefinitionQuery.processDefinitionWithoutTenantId();
          }
        
          previousProcessDefinition = (ProcessDefinitionEntity) previousProcessDefinitionQuery.singleResult();
          previousVersion--;
          
        }
        
        // TODO: cleanup in a util or something like that
        if (previousProcessDefinition != null) {

          org.activiti.bpmn.model.Process previousProcess = ProcessDefinitionUtil.getProcess(previousProcessDefinition.getId());
          if (CollectionUtils.isNotEmpty(previousProcess.getFlowElements())) {
            List<StartEvent> startEvents = previousProcess.findFlowElementsOfType(StartEvent.class);
            if (CollectionUtils.isNotEmpty(startEvents)) {
              for (StartEvent startEvent : startEvents) {
                if (CollectionUtils.isNotEmpty(startEvent.getEventDefinitions())) {
                  EventDefinition eventDefinition = startEvent.getEventDefinitions().get(0);
                  if (eventDefinition instanceof TimerEventDefinition) {
                    TimerEventDefinition timerEventDefinition = (TimerEventDefinition) eventDefinition;
                    TimerEntity timer = TimerUtil.createTimerEntityForTimerEventDefinition((TimerEventDefinition) eventDefinition, false, null, TimerStartEventJobHandler.TYPE,
                        TimerEventHandler.createConfiguration(startEvent.getId(), timerEventDefinition.getEndDate()));
                    
                    if (timer != null) {
                      timer.setProcessDefinitionId(previousProcessDefinition.getId());
  
                      if (previousProcessDefinition.getTenantId() != null) {
                        timer.setTenantId(previousProcessDefinition.getTenantId());
                      }
  
                      Context.getCommandContext().getJobEntityManager().schedule(timer);
                    }
                  }
                }
              }
            }

          }

        }
      }

      // remove message event subscriptions:
      EventSubscriptionEntityManager eventSubscriptionEntityManager = Context.getCommandContext().getEventSubscriptionEntityManager();
      List<EventSubscriptionEntity> findEventSubscriptionsByConfiguration = eventSubscriptionEntityManager 
          .findEventSubscriptionsByConfiguration(MessageEventHandler.EVENT_HANDLER_TYPE, processDefinition.getId(), processDefinition.getTenantId());
      for (EventSubscriptionEntity eventSubscriptionEntity : findEventSubscriptionsByConfiguration) {
        eventSubscriptionEntityManager.delete(eventSubscriptionEntity);
      }
    }

    getResourceManager().deleteResourcesByDeploymentId(deploymentId);

    getDbSqlSession().delete("deleteDeployment", deploymentId);
  }

  @Override
  public DeploymentEntity findLatestDeploymentByName(String deploymentName) {
    List<?> list = getDbSqlSession().selectList("selectDeploymentsByName", deploymentName, 0, 1);
    if (list != null && !list.isEmpty()) {
      return (DeploymentEntity) list.get(0);
    }
    return null;
  }

  @Override
  public DeploymentEntity findDeploymentById(String deploymentId) {
    return (DeploymentEntity) getDbSqlSession().selectOne("selectDeploymentById", deploymentId);
  }

  @Override
  public long findDeploymentCountByQueryCriteria(DeploymentQueryImpl deploymentQuery) {
    return (Long) getDbSqlSession().selectOne("selectDeploymentCountByQueryCriteria", deploymentQuery);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Deployment> findDeploymentsByQueryCriteria(DeploymentQueryImpl deploymentQuery, Page page) {
    final String query = "selectDeploymentsByQueryCriteria";
    return getDbSqlSession().selectList(query, deploymentQuery, page);
  }

  @Override
  public List<String> getDeploymentResourceNames(String deploymentId) {
    return getDbSqlSession().getSqlSession().selectList("selectResourceNamesByDeploymentId", deploymentId);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Deployment> findDeploymentsByNativeQuery(Map<String, Object> parameterMap, int firstResult, int maxResults) {
    return getDbSqlSession().selectListWithRawParameter("selectDeploymentByNativeQuery", parameterMap, firstResult, maxResults);
  }

  @Override
  public long findDeploymentCountByNativeQuery(Map<String, Object> parameterMap) {
    return (Long) getDbSqlSession().selectOne("selectDeploymentCountByNativeQuery", parameterMap);
  }

}
