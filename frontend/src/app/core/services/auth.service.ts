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

  constructor() {
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
    const accessToken = localStorage.getItem('accessToken');
    const refreshToken = localStorage.getItem('refreshToken');
    if (accessToken && refreshToken) {
      this.http.post('/api/auth/logout',
        { refreshToken },
        { headers: { Authorization: `Bearer ${accessToken}` } }
      ).subscribe();
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
      const refreshIn = payload.exp * 1000 - Date.now() - 60_000; // 1 min before expiry
      if (refreshIn > 0) {
        this.refreshTimer = setTimeout(() => this.refresh().subscribe(), refreshIn);
      }
    } catch { /* malformed token — skip */ }
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
  }
}
