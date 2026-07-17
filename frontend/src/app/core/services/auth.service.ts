import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { map, tap } from 'rxjs/operators';
import { Observable } from 'rxjs';
import { AuthResponse, LoginRequest, RegisterRequest } from '../models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  private _isLoggedIn = signal(!!localStorage.getItem('accessToken'));
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  private static readonly IDLE_TIMEOUT_MS = 15 * 60_000;
  private lastActivity = Date.now();

  // Countdown shown before an idle logout: seconds remaining, or null when hidden.
  // A modal reads this signal and calls stayLoggedIn() / logout() on the buttons.
  private static readonly WARNING_SECONDS = 60;
  private _sessionWarning = signal<number | null>(null);
  readonly sessionWarning = this._sessionWarning.asReadonly();
  private warningInterval: ReturnType<typeof setInterval> | null = null;

  constructor() {
    // Genuine interaction only (not mousemove) marks the user as active.
    for (const evt of ['mousedown', 'keydown', 'touchstart', 'scroll'] as const) {
      window.addEventListener(evt, () => (this.lastActivity = Date.now()), { passive: true });
    }
    const existing = localStorage.getItem('accessToken');
    if (existing) this.scheduleRefresh(existing);
  }
  readonly isLoggedIn = this._isLoggedIn.asReadonly();

  login(req: LoginRequest): Observable<void> {
    return this.http.post<AuthResponse>('/api/auth/login', req).pipe(
      tap(res => this.storeTokens(res)),
      map(() => void 0)
    );
  }

  register(req: RegisterRequest): Observable<void> {
    return this.http.post<AuthResponse>('/api/auth/register', req).pipe(
      tap(res => this.storeTokens(res)),
      map(() => void 0)
    );
  }

  refresh(): Observable<void> {
    const refreshToken = localStorage.getItem('refreshToken');
    return this.http.post<AuthResponse>('/api/auth/refresh', { refreshToken }).pipe(
      tap(res => this.storeTokens(res)),
      map(() => void 0)
    );
  }

  logout(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.clearWarning();
    const accessToken = localStorage.getItem('accessToken');
    const refreshToken = localStorage.getItem('refreshToken');
    if (accessToken && refreshToken) {
      this.http.post('/api/auth/logout',
        { refreshToken },
        { headers: { Authorization: `Bearer ${accessToken}` } }
      ).subscribe({ error: () => {} });
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    this._isLoggedIn.set(false);
    this.router.navigate(['/login']);
  }

  private scheduleRefresh(accessToken: string): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    try {
      const payload = JSON.parse(atob(accessToken.split('.')[1]));
      const msToExpiry = payload.exp * 1000 - Date.now();
      if (msToExpiry <= 0) {
        this.refreshOrLogout();
      } else {
        const refreshIn = Math.max(msToExpiry - 60_000, msToExpiry / 2);
        this.refreshTimer = setTimeout(() => this.refreshOrLogout(), refreshIn);
      }
    } catch { /* malformed token — skip */ }
  }

  // Timer-driven renewal, gated on activity: renew silently while the user is
  // active, otherwise warn with a countdown before ending the session.
  // Interactive 401s still refresh via the interceptor — making a request is
  // itself proof of activity.
  private refreshOrLogout(): void {
    if (Date.now() - this.lastActivity > AuthService.IDLE_TIMEOUT_MS) {
      this.startIdleWarning();
    } else {
      this.refresh().subscribe({ error: () => this.logout() });
    }
  }

  private startIdleWarning(): void {
    if (this.warningInterval) return; // already counting down
    let remaining = AuthService.WARNING_SECONDS;
    this._sessionWarning.set(remaining);
    this.warningInterval = setInterval(() => {
      remaining -= 1;
      if (remaining <= 0) {
        this.logout(); // clears the warning + redirects
      } else {
        this._sessionWarning.set(remaining);
      }
    }, 1000);
  }

  private clearWarning(): void {
    if (this.warningInterval) {
      clearInterval(this.warningInterval);
      this.warningInterval = null;
    }
    this._sessionWarning.set(null);
  }

  // "Stay logged in" from the warning modal — count it as activity and refresh.
  stayLoggedIn(): void {
    this.lastActivity = Date.now();
    this.clearWarning();
    this.refresh().subscribe({ error: () => this.logout() });
  }

  getAccessToken(): string | null {
    return localStorage.getItem('accessToken');
  }

  isAdmin(): boolean {
    const token = this.getAccessToken();
    if (!token) return false;
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload.role === 'ADMIN';
    } catch {
      return false;
    }
  }

  private storeTokens(res: AuthResponse): void {
    localStorage.setItem('accessToken', res.accessToken);
    localStorage.setItem('refreshToken', res.refreshToken);
    this._isLoggedIn.set(true);
    this.scheduleRefresh(res.accessToken);
    // Any successful refresh (timer, interceptor 401, or "stay logged in")
    // dismisses a lingering idle warning so its countdown can't log out a
    // session that was just renewed.
    this.clearWarning();
  }
}
