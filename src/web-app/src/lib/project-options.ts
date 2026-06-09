import { formatWorkdir } from "./format-workdir"

export interface ProjectPath {
  path: string
}

export interface ProjectPathOption {
  path: string
  label: string
}

export function buildProjectOptions(projects: ProjectPath[], homeDir?: string | null): ProjectPathOption[] {
  return projects.map((project) => ({
    path: project.path,
    label: formatWorkdir(project.path, homeDir ?? undefined),
  }))
}
