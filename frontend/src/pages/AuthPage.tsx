import { FormEvent, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { api, errorText, User } from "../api";

interface Props {
  mode: "login" | "register";
  onAuth: (token: string, user: User) => void;
}

export default function AuthPage({ mode, onAuth }: Props) {
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const isRegister = mode === "register";

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      const body = isRegister
        ? { username, display_name: displayName, password }
        : { username, password };
      const res = await api<{ token: string; user: User }>(`/auth/${mode}`, {
        method: "POST",
        body,
      });
      onAuth(res.token, res.user);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card narrow" onSubmit={submit}>
      <div>
        <h1>{isRegister ? "Регистрация" : "С возвращением"}</h1>
        <p className="muted">Бронирование времени на стиральные машины</p>
      </div>
      <label>
        Логин
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          autoComplete="username"
          pattern="[A-Za-z0-9_.\-]+"
          title="Латинские буквы, цифры, _ . -"
          minLength={3}
          maxLength={32}
          required
          autoFocus
        />
      </label>
      {isRegister && (
        <label>
          Имя (видно другим участникам)
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            maxLength={64}
            required
          />
        </label>
      )}
      <label>
        Пароль
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={isRegister ? "new-password" : "current-password"}
          minLength={isRegister ? 6 : undefined}
          required
        />
      </label>
      {error && <div className="error">{error}</div>}
      <button className="primary" disabled={busy}>
        {isRegister ? "Создать аккаунт" : "Войти"}
      </button>
      <p className="muted">
        {isRegister ? "Уже есть аккаунт? " : "Нет аккаунта? "}
        <Link to={isRegister ? "/login" : "/register"} state={location.state}>
          {isRegister ? "Войти" : "Зарегистрироваться"}
        </Link>
      </p>
    </form>
  );
}
