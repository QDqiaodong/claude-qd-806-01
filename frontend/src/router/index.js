import { createRouter, createWebHistory } from 'vue-router'
import EnvironmentDashboard from '../views/EnvironmentDashboard.vue'
import EnvironmentTickets from '../views/EnvironmentTickets.vue'
import FruitingTimeline from '../views/FruitingTimeline.vue'
import ContaminationGrid from '../views/ContaminationGrid.vue'
import RackModel from '../views/RackModel.vue'
import BagAllocation from '../views/BagAllocation.vue'

const routes = [
  { path: '/', redirect: '/environment' },
  { path: '/environment', component: EnvironmentDashboard, meta: { title: '菇房环境' } },
  { path: '/tickets', component: EnvironmentTickets, meta: { title: '越限处置' } },
  { path: '/fruiting', component: FruitingTimeline, meta: { title: '出菇潮次' } },
  { path: '/contamination', component: ContaminationGrid, meta: { title: '污染排查' } },
  { path: '/racks', component: RackModel, meta: { title: '培养架层架' } },
  { path: '/allocation', component: BagAllocation, meta: { title: '入房分配' } }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
