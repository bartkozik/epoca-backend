package pl.iterators.epoca.workunit.domain

import pl.iterators.epoca.client.domain.ClientDomain.{ClientId, ClientName}
import pl.iterators.epoca.project.domain.ProjectDomain.{ProjectId, ProjectName}
import pl.iterators.epoca.workunit.domain.WorkUnitDomain.WorkUnitTaskId

/** Historical PM values only: original source and task inheritance flags were not retained by V23. */
final case class WorkUnitOverrideLegacySnapshot(
  projectId: Option[ProjectId],
  projectName: Option[ProjectName],
  clientId: Option[ClientId],
  clientName: Option[ClientName],
  taskId: Option[WorkUnitTaskId])
