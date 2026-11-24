import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Project } from '../api/types'
import { useSession } from './session'

/** 시스템마다 프로젝트 목록. 사이드바가 쓴다. 한 번 읽으면 기억한다. */
export const useWorkspace = defineStore('workspace', () => {
  const projects = ref<Record<number, Project[]>>({})

  async function load(systemId: number): Promise<Project[]> {
    if (!projects.value[systemId]) {
      projects.value[systemId] = await useSession().client.projects(systemId)
    }
    return projects.value[systemId] ?? []
  }

  function find(projectId: number): Project | undefined {
    return Object.values(projects.value).flat().find((p) => p.id === projectId)
  }

  return { projects, load, find }
})
