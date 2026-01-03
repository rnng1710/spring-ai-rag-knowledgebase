import { createRouter, createWebHistory } from "vue-router";
import LoginView from "../views/LoginView.vue";
import UserChatView from "../views/UserChatView.vue";
import AdminIndexView from "../views/AdminIndexView.vue";
import AdminStatusView from "../views/AdminStatusView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/login" },
    { path: "/login", component: LoginView },
    { path: "/user/chat", component: UserChatView, meta: { role: "user" } },
    { path: "/admin/index", component: AdminIndexView, meta: { role: "admin" } },
    { path: "/admin/status", component: AdminStatusView, meta: { role: "admin" } }
  ]
});

router.beforeEach((to) => {
  if (to.path === "/login") {
    return true;
  }
  const token = localStorage.getItem("auth_basic");
  const role = localStorage.getItem("auth_role");
  if (!token || !role) {
    return "/login";
  }
  const requiredRole = to.meta?.role;
  if (requiredRole && requiredRole !== role) {
    return "/login";
  }
  return true;
});

export default router;
