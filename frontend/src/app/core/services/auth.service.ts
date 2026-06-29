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

  logout(): void {
    const accessToken = localStorage.getItem('accessToken');
    if (accessToken) {
      this.http.post('/api/auth/logout', { accessToken }).subscribe();
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    this._isLoggedIn.set(false);
    this.router.navigate(['/login']);
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
  }
}
