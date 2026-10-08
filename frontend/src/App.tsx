import { Navigate, Route, Routes } from "react-router-dom";
import { Footer } from "./components/Footer";
import { Header } from "./components/Header";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { AccountPage } from "./pages/AccountPage";
import { ContactPage } from "./pages/ContactPage";
import { EventCalendarPage } from "./pages/EventCalendarPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { EventListPage } from "./pages/EventListPage";
import { LoginPage } from "./pages/LoginPage";
import { MyTicketsPage } from "./pages/MyTicketsPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { OAuth2RedirectPage } from "./pages/OAuth2RedirectPage";
import { PrivacyPage } from "./pages/PrivacyPage";
import { SignupPage } from "./pages/SignupPage";
import { TermsPage } from "./pages/TermsPage";
import { TransfersPage } from "./pages/TransfersPage";
import { WishlistPage } from "./pages/WishlistPage";

function App() {
  return (
    <>
      <Header />
      <main className="main">
        <Routes>
          <Route path="/" element={<EventListPage />} />
          <Route path="/calendar" element={<EventCalendarPage />} />
          <Route path="/events/:eventId" element={<EventDetailPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/oauth2/redirect" element={<OAuth2RedirectPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/contact" element={<ContactPage />} />
          <Route path="/orders" element={<Navigate to="/tickets?tab=orders" replace />} />
          <Route
            path="/account"
            element={
              <ProtectedRoute>
                <AccountPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/tickets"
            element={
              <ProtectedRoute>
                <MyTicketsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/transfers"
            element={
              <ProtectedRoute>
                <TransfersPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/wishlist"
            element={
              <ProtectedRoute>
                <WishlistPage />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </main>
      <Footer />
    </>
  );
}

export default App;
