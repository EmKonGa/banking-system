import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { BehaviorSubject, catchError, filter, switchMap, take, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

let isRefreshing = false;
const refreshed$ = new BehaviorSubject<boolean>(false);

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
        refreshed$.next(false);

        return authSvc.refresh().pipe(
          switchMap(() => {
            isRefreshing = false;
            refreshed$.next(true);
            return next(withToken(localStorage.getItem('accessToken')!));
          }),
          catchError(refreshErr => {
            isRefreshing = false;
            authSvc.logout();
            return throwError(() => refreshErr);
          })
        );
      }

      // Another request already triggered refresh — wait for it then retry
      return refreshed$.pipe(
        filter(done => done),
        take(1),
        switchMap(() => next(withToken(localStorage.getItem('accessToken')!)))
      );
    })
  );
};
