import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('./views/Login.vue') },
  { path: '/', component: () => import('./views/Home.vue') },
  { path: '/venue/:id', component: () => import('./views/VenueDetail.vue') },
  { path: '/orders', component: () => import('./views/Orders.vue') }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
