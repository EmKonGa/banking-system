import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then(m => m.LoginPage)
  },
  {
    path: 'register',
    loadComponent: () => import('./pages/register/register').then(m => m.RegisterPage)
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell').then(m => m.ShellLayout),
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./pages/dashboard/dashboard').then(m => m.DashboardPage)
      },
      {
        path: 'accounts',
        loadComponent: () => import('./pages/accounts/accounts').then(m => m.AccountsPage)
      },
      {
        path: 'transfer',
        loadComponent: () => import('./pages/transfer/transfer').then(m => m.TransferPage)
      },
      {
        path: 'notifications',
        loadComponent: () => import('./pages/notifications/notifications').then(m => m.NotificationsPage)
      }
    ]
  },
  { path: '**', redirectTo: 'dashboard' }
];
