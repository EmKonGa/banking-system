import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { BehaviorSubject, catchError, filter, switchMap, take, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

let isRefreshing = false;
// 'pending' while a refresh is in flight; waiters resume on 'ok', fail fast on 'fail'
// so they never hang when the refresh itself fails (which logs the user out).
const refreshState$ = new BehaviorSubject<'pending' | 'ok' | 'fail'>('pending');

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authSvc = inject(AuthService);

  const isAuthUrl = req.url.includes('/api/auth/');

  const withToken = (token: string) =>
    req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });

  const token = localStorage.getItem('accessToken');
  const authReq = token && !isAuthUrl ? withToken(token) : req;

  return next(authReq).pipe(
    catchError(err => {
      if (!(err instanceof HttpErrorResponse) || err.status !== 401 || isAuthUrl) {
        return throwError(() => err);
      }

      if (!isRefreshing) {
        isRefreshing = true;
        refreshState$.next('pending');

        return authSvc.refresh().pipe(
          switchMap(() => {
            isRefreshing = false;
            refreshState$.next('ok');
            return next(withToken(localStorage.getItem('accessToken')!));
          }),
          catchError(refreshErr => {
            isRefreshing = false;
            refreshState$.next('fail');
            authSvc.logout(); // clears tokens + redirects to /login
            return throwError(() => refreshErr);
          })
        );
      }

      // Another request already triggered refresh — wait for its outcome, then
      // retry on success or propagate the original 401 on failure.
      return refreshState$.pipe(
        filter(state => state !== 'pending'),
        take(1),
        switchMap(state =>
          state === 'ok'
            ? next(withToken(localStorage.getItem('accessToken')!))
            : throwError(() => err)
        )
      );
    })
  );
};
