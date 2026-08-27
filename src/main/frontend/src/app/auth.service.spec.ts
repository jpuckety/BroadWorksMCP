import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService, PORTAL_RETURN_URL_KEY, PortalUser } from './auth.service';

const me: PortalUser = { email: 'user@example.com', name: 'User Name', picture: 'https://example.com/p.png' };

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('loads the signed-in user from GET /api/portal/me', () => {
    let actual: PortalUser | null | undefined;
    service.me().subscribe((value) => (actual = value));

    const req = http.expectOne('/api/portal/me');
    expect(req.request.method).toBe('GET');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(me);

    expect(actual).toEqual(me);
    expect(service.user()).toEqual(me);
    expect(service.isAuthenticated()).toBe(true);
  });

  it('treats a 401 from /api/portal/me as signed out', () => {
    let actual: PortalUser | null | undefined = me;
    service.me().subscribe((value) => (actual = value));

    http.expectOne('/api/portal/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(actual).toBeNull();
    expect(service.user()).toBeNull();
    expect(service.isAuthenticated()).toBe(false);
  });

  it('posts logout and clears the user', () => {
    service.me().subscribe();
    http.expectOne('/api/portal/me').flush(me);
    expect(service.isAuthenticated()).toBe(true);

    let completed = false;
    service.logout().subscribe(() => (completed = true));

    const req = http.expectOne('/api/portal/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBe(true);
    expect(service.user()).toBeNull();
    expect(service.isAuthenticated()).toBe(false);
  });

  it('stashes and consumes portalReturnUrl in sessionStorage', () => {
    service.stashReturnUrl('/approvals/a1');
    expect(sessionStorage.getItem(PORTAL_RETURN_URL_KEY)).toBe('/approvals/a1');
    expect(service.consumeReturnUrl()).toBe('/approvals/a1');
    expect(sessionStorage.getItem(PORTAL_RETURN_URL_KEY)).toBeNull();
    expect(service.consumeReturnUrl()).toBeNull();
  });

  it('does not stash login or home as a return URL', () => {
    service.stashReturnUrl('/');
    service.stashReturnUrl('/login');
    service.stashReturnUrl('/login?returnUrl=/approvals/a1');
    expect(sessionStorage.getItem(PORTAL_RETURN_URL_KEY)).toBeNull();
  });
});
