import { createRouter, createWebHistory } from "vue-router";
import LoginView from "../views/LoginView.vue";
import UserChatView from "../views/UserChatView.vue";
import AdminIndexView from "../views/AdminIndexView.vue";
import AdminStatusView from "../views/AdminStatusView.vue";
import AdminLayout from "../layout/AdminLayout.vue";
import AdminDocumentsView from "../views/AdminDocumentsView.vue";
import { AppRole, clearAuthSession, getAccessToken, getRoleFromAccessToken, getUsernameFromAccessToken, isTokenExpired } from "../utils/auth";
import { ensureValidAccessToken } from "../api/client";

import UserManagement from "../views/admin/UserManagement.vue";
import AdminEvaluationView from "../views/admin/AdminEvaluationView.vue";

interface AppRouteMeta {
  role?: AppRole;
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/login" },
    { path: "/login", component: LoginView },
    { path: "/user/chat", component: UserChatView, meta: { role: "USER" as AppRole } },
    {
      path: "/admin",
      component: AdminLayout,
      meta: { role: "ADMIN" as AppRole },
      redirect: "/admin/index",
      children: [
        { path: "index", component: AdminIndexView },
        { path: "documents", component: AdminDocumentsView },
        { path: "users", component: UserManagement },
        { path: "status", component: AdminStatusView },
        { path: "evaluation", component: AdminEvaluationView }
      ]
    }
  ]
});

router.beforeEach(async (to) => {
  let token = getAccessToken();
  if (token && isTokenExpired(token)) {
    try {
      token = await ensureValidAccessToken();
    } catch {
      token = null;
    }
  }

  const role = getRoleFromAccessToken(token);
  const username = getUsernameFromAccessToken(token);
  const hasValidToken = !!token && !!role && !!username && !isTokenExpired(token);
  if (!hasValidToken) {
    clearAuthSession();
    if (to.path === "/login") {
      return true;
    }
    return "/login";
  }

  localStorage.setItem("auth_user", username);
  localStorage.setItem("auth_role", role);

  if (to.path === "/login") {
    return role === "ADMIN" ? "/admin/index" : "/user/chat";
  }

  const requiredRole = to.matched
    .map((record) => (record.meta as AppRouteMeta).role)
    .find((metaRole): metaRole is AppRole => !!metaRole);

  if (requiredRole && role !== requiredRole) {
    return role === "ADMIN" ? "/admin/index" : "/user/chat";
  }

  return true;
});

export default router;
