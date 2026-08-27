import { Routes } from '@angular/router';
import { ApprovalComponent } from './approval.component';
import { authGuard } from './auth.guard';
import { ConnectionFormComponent } from './connection-form.component';
import { ConnectionsListComponent } from './connections-list.component';
import { LoginComponent } from './login.component';
import { SetPasswordComponent } from './set-password.component';

/**
 * Client-side routes for the portal. Paths are relative to the app's base href (`/portal/`), so the
 * server's SPA fallback controller forwards these deep links to `index.html`.
 * `/login` is public; every other screen waits for `GET /api/portal/me`.
 */
export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', component: ConnectionsListComponent, canActivate: [authGuard] },
  { path: 'new', component: ConnectionFormComponent, canActivate: [authGuard] },
  { path: 'approvals/:id', component: ApprovalComponent, canActivate: [authGuard] },
  { path: ':id/edit', component: ConnectionFormComponent, canActivate: [authGuard] },
  { path: ':id/password', component: SetPasswordComponent, canActivate: [authGuard] },
  { path: '**', redirectTo: '' }
];
