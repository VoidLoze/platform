import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import { AssignmentAlertsProvider } from "./context/AssignmentAlertsContext";
import { ChatInboxProvider } from "./context/ChatInboxContext";
import { ThemeProvider } from "./context/ThemeContext";
import { AppRoutes } from "./router";
import "./index.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <ThemeProvider>
        <AuthProvider>
          <AssignmentAlertsProvider>
            <ChatInboxProvider>
              <AppRoutes />
            </ChatInboxProvider>
          </AssignmentAlertsProvider>
        </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  </StrictMode>,
);
