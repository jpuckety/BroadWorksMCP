import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Approval, ApprovalService } from './approval.service';

describe('ApprovalService', () => {
  let service: ApprovalService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(ApprovalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('gets an approval by id', () => {
    const expected = { id: 'a1', action: "delete user 'u1'", status: 'PENDING' as const };
    let actual: Approval | undefined;
    service.get('a1').subscribe((value) => (actual = value));

    const req = http.expectOne('/api/portal/approvals/a1');
    expect(req.request.method).toBe('GET');
    req.flush(expected);
    expect(actual).toEqual(expected);
  });

  it('posts a Confirm decision', () => {
    const expected = { id: 'a1', action: "delete user 'u1'", status: 'APPROVED' as const };
    let actual: Approval | undefined;
    service.decide('a1', 'APPROVED').subscribe((value) => (actual = value));

    const req = http.expectOne('/api/portal/approvals/a1');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ decision: 'APPROVED' });
    req.flush(expected);
    expect(actual).toEqual(expected);
  });
});
