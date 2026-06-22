import { showErrorToast } from "./toast";

export interface ApiErrorResponse {
    timestamp: string;
    status: number;
    error: string;
    message: string;
    path: string;
}

/**
 * Handles API error responses by parsing the error and showing a toast notification.
 * Works with Spring Boot error response format.
 *
 * @param response The fetch Response object
 * @param defaultMessage Fallback message if error parsing fails
 * @throws Error with the parsed message from the server
 */
export async function handleApiError(response: Response, defaultMessage: string): Promise<never> {
    let errorMessage = defaultMessage;

    try {
        const contentType = response.headers.get("content-type");

        if (contentType && contentType.includes("application/json")) {
            const errorData: ApiErrorResponse = await response.json();
            // Use the message from the server error response
            errorMessage = errorData.message || errorMessage;
        } else {
            // If not JSON, try to get text
            const text = await response.text();
            if (text) {
                errorMessage = text;
            }
        }
    } catch (parseError) {
        // If parsing fails, use the default message
        console.error("Failed to parse error response:", parseError);
    }

    // Show the error toast
    showErrorToast(errorMessage);

    // Throw an error with the message so calling code can handle it if needed
    throw new Error(errorMessage);
}