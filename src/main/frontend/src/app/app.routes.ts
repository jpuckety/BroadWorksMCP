import { Routes } from '@angular/router';
import { ApprovalComponent } from './approval.component';
import { ConnectionFormComponent } from './connection-form.component';
import { ConnectionsListComponent } from './connections-list.component';
import { SetPasswordComponent } from './set-password.component';

/**
 * Client-side routes for the portal. Paths are relative to the app's base href (`/portal/`), so the
 * server's SPA fallback controller forwards these deep links to `index.html`.
 */
export const routes: Routes = [
  { path: '', component: ConnectionsListComponent },
  { path: 'new', component: ConnectionFormComponent },
  { path: 'approvals/:id', component: ApprovalComponent },
  { path: ':id/edit', component: ConnectionFormComponent },
  { path: ':id/password', component: SetPasswordComponent },
  { path: '**', redirectTo: '' }
];
