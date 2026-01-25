import { createRouter, createWebHistory } from "vue-router";
import LoginView from "../views/LoginView.vue";
import UserChatView from "../views/UserChatView.vue";
import AdminIndexView from "../views/AdminIndexView.vue";
import AdminStatusView from "../views/AdminStatusView.vue";
import AdminLayout from "../layout/AdminLayout.vue";
import AdminDocumentsView from "../views/AdminDocumentsView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/login" },
    { path: "/login", component: LoginView },
    { path: "/user/chat", component: UserChatView, meta: { role: "user" } },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { role: "admin" },
      redirect: "/admin/index",
      children: [
        { path: "index", component: AdminIndexView },
        { path: "documents", component: AdminDocumentsView },
        { path: "status", component: AdminStatusView }
      ]
    }
  ]
});

router.beforeEach((to) => {
  if (to.path === "/login") {
    return true;
  }
  const token = localStorage.getItem("auth_access_token");
  const role = localStorage.getItem("auth_role");
  if (!token || !role) {
    return "/login";
  }
  const requiredRole = to.matched.some(record => record.meta.role)
    ? to.matched.find(record => record.meta.role)?.meta.role
    : to.meta.role;

  // Simple check: if any matched route requires admin, current user must be admin
  // Currently structure is simple.
  const routeRole = to.matched.find(r => r.meta.role)?.meta.role;
  if (routeRole && routeRole !== role) {
    // User trying to access admin
    return "/login";
  }

  return true;
});

export default router;
