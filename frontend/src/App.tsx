import { Route, Routes } from "react-router-dom";
import { Header } from "./components/Header";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { EventDetailPage } from "./pages/EventDetailPage";
import { EventListPage } from "./pages/EventListPage";
import { LoginPage } from "./pages/LoginPage";
import { MyOrdersPage } from "./pages/MyOrdersPage";
import { OAuth2RedirectPage } from "./pages/OAuth2RedirectPage";
import { SignupPage } from "./pages/SignupPage";

function App() {
  return (
    <>
      <Header />
      <main className="main">
        <Routes>
          <Route path="/" element={<EventListPage />} />
          <Route path="/events/:eventId" element={<EventDetailPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/oauth2/redirect" element={<OAuth2RedirectPage />} />
          <Route
            path="/orders"
            element={
              <ProtectedRoute>
                <MyOrdersPage />
              </ProtectedRoute>
            }
          />
        </Routes>
      </main>
    </>
  );
}

export default App;
