import { toast } from "sonner"

/**
 * Display an error toast notification
 * @param message The error message to display
 * @param error Optional error object for logging/debugging
 */
export function showErrorToast(message: string, error?: unknown) {
  // Log the full error for debugging
  if (error) {
    console.error(message, error)
  }

  toast.error(message)
}

/**
 * Display a success toast notification
 * @param message The success message to display
 */
export function showSuccessToast(message: string) {
  toast.success(message)
}

/**
 * Display an info toast notification
 * @param message The info message to display
 */
export function showInfoToast(message: string) {
  toast.info(message)
}

/**
 * Display a warning toast notification
 * @param message The warning message to display
 */
export function showWarningToast(message: string) {
  toast.warning(message)
}