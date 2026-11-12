import { useEffect, useState } from "react";
import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { api, getToken, setToken, User } from "./api";
import AuthPage from "./pages/AuthPage";
import HomePage from "./pages/HomePage";
import LaundryPage from "./pages/LaundryPage";
import { Avatar, UiHost } from "./ui";

function Logo() {
  return (
    <svg className="logo" viewBox="0 0 32 32" aria-hidden>
      <rect x="4" y="2" width="24" height="28" rx="6" fill="currentColor" />
      <circle cx="16" cy="18" r="7" fill="none" stroke="#fff" strokeWidth="2.2" />
      <path d="M11 18c2-2 3.5 2 5 0s3-2 5 0" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
      <circle cx="9.5" cy="7.5" r="1.4" fill="#fff" />
      <circle cx="14" cy="7.5" r="1.4" fill="#fff" />
    </svg>
  );
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(!!getToken());
  const location = useLocation();

  useEffect(() => {
    if (!getToken()) return;
    api<User>("/auth/me")
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  const onAuth = (token: string, u: User) => {
    setToken(token);
    setUser(u);
  };
  const logout = () => {
    setToken(null);
    setUser(null);
  };

  if (loading) return <div className="center muted">Загрузка…</div>;

  // после входа возвращаем туда, куда человек шёл (например, по ссылке-приглашению)
  const next = (location.state as { next?: string } | null)?.next ?? "/";
  const guard = (page: JSX.Element) =>
    user ? page : <Navigate to="/login" state={{ next: location.pathname }} replace />;

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            <Logo />
            Стиралка
          </Link>
          {user && (
            <div className="row">
              <Avatar name={user.display_name} />
              <span className="user-name">
                {user.display_name}
                <span className="muted small"> @{user.username}</span>
              </span>
              <button className="ghost" onClick={logout}>
                Выйти
              </button>
            </div>
          )}
        </div>
      </header>
      <main className="container">
        <Routes>
          <Route
            path="/login"
            element={user ? <Navigate to={next} replace /> : <AuthPage mode="login" onAuth={onAuth} />}
          />
          <Route
            path="/register"
            element={
              user ? <Navigate to={next} replace /> : <AuthPage mode="register" onAuth={onAuth} />
            }
          />
          <Route path="/" element={guard(<HomePage />)} />
          <Route path="/l/:code" element={guard(user ? <LaundryPage user={user} /> : <></>)} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <UiHost />
    </>
  );
}
