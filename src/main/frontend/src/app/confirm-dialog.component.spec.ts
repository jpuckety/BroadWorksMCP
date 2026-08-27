import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  let component: ConfirmDialogComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConfirmDialogComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(ConfirmDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('is hidden until opened', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.modal-overlay')).toBeNull();
    expect(compiled.querySelector('[role="dialog"]')).toBeNull();
  });

  it('renders title, message, Cancel, and a danger Confirm', () => {
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('title', 'Delete connection');
    fixture.componentRef.setInput('message', 'This cannot be undone.');
    fixture.componentRef.setInput('confirmText', 'Delete');
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.dialog-title')?.textContent).toContain('Delete connection');
    expect(compiled.querySelector('.dialog-message')?.textContent).toContain('This cannot be undone.');
    expect(compiled.querySelector('.btn-secondary')?.textContent?.trim()).toBe('Cancel');
    const confirm = compiled.querySelector<HTMLButtonElement>('.btn-danger');
    expect(confirm?.textContent?.trim()).toBe('Delete');
  });

  it('emits cancel from Cancel and overlay, confirm from Confirm', () => {
    fixture.componentRef.setInput('isOpen', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const cancel = vi.fn();
    const confirm = vi.fn();
    component.cancel.subscribe(cancel);
    component.confirm.subscribe(confirm);

    compiled.querySelector<HTMLButtonElement>('.btn-secondary')?.click();
    compiled.querySelector<HTMLButtonElement>('.btn-danger')?.click();
    compiled.querySelector<HTMLElement>('.modal-overlay')?.click();

    expect(cancel).toHaveBeenCalledTimes(2);
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it('does not emit while loading and disables both buttons', () => {
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const cancel = vi.fn();
    const confirm = vi.fn();
    component.cancel.subscribe(cancel);
    component.confirm.subscribe(confirm);

    const cancelBtn = compiled.querySelector<HTMLButtonElement>('.btn-secondary');
    const confirmBtn = compiled.querySelector<HTMLButtonElement>('.btn-danger');
    expect(cancelBtn?.disabled).toBe(true);
    expect(confirmBtn?.disabled).toBe(true);
    expect(confirmBtn?.textContent).toContain('Working…');

    cancelBtn?.click();
    confirmBtn?.click();
    compiled.querySelector<HTMLElement>('.modal-overlay')?.click();
    expect(cancel).not.toHaveBeenCalled();
    expect(confirm).not.toHaveBeenCalled();
  });
});
