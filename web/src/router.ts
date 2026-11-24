import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './stores/session'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'home', component: () => import('./views/HomeView.vue') },
    {
      path: '/systems/:systemId/projects/:projectId/issues',
      name: 'issues',
      component: () => import('./views/IssueListView.vue'),
      props: (route) => ({ systemId: Number(route.params.systemId), projectId: Number(route.params.projectId) }),
    },
    {
      path: '/systems/:systemId/projects/:projectId/releases',
      name: 'releases',
      component: () => import('./views/ReleaseListView.vue'),
      props: (route) => ({ systemId: Number(route.params.systemId), projectId: Number(route.params.projectId) }),
    },
    {
      path: '/systems/:systemId/issues/:issueId',
      name: 'issue',
      component: () => import('./views/IssueDetailView.vue'),
      props: (route) => ({ systemId: Number(route.params.systemId), issueId: Number(route.params.issueId) }),
    },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const session = useSession()
  if (await session.restore().catch(() => false)) return true
  return { name: 'login', query: { next: to.fullPath } }
})
