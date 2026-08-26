import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ApprovalComponent } from './approval.component';
import { ApprovalService } from './approval.service';

describe('ApprovalComponent', () => {
  let fixture: ComponentFixture<ApprovalComponent>;
  let service: { get: ReturnType<typeof vi.fn>; decide: ReturnType<typeof vi.fn> };

  async function setup(id: string | null, getImpl?: ApprovalService['get']): Promise<void> {
    service = {
      get: vi.fn(getImpl ?? (() => of({
        id: 'a1',
        action: "delete user 'u1'",
        status: 'PENDING' as const
      }))),
      decide: vi.fn(() => of({
        id: 'a1',
        action: "delete user 'u1'",
        status: 'APPROVED' as const
      }))
    };

    await TestBed.configureTestingModule({
      imports: [ApprovalComponent],
      providers: [
        { provide: ApprovalService, useValue: service },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => id } } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ApprovalComponent);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('loads the action and confirms', async () => {
    await setup('a1');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain("delete user 'u1'");
    expect(compiled.textContent).toContain('Confirm');

    compiled.querySelector<HTMLButtonElement>('.btn.primary')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.decide).toHaveBeenCalledWith('a1', 'APPROVED');
    expect(compiled.textContent).toContain('You can close this tab');
    expect(compiled.textContent).toContain('The waiting tool will resume');
  });

  it('shows a not-found error for a 404', async () => {
    await setup('missing', () => throwError(() => ({ status: 404 })));
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('This approval was not found or has expired.');
  });
});
