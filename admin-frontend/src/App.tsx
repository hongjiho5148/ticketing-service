import { Navigate, Route, Routes } from "react-router-dom";
import { Header } from "./components/Header";
import { RequireAdmin } from "./components/RequireAdmin";
import { CouponsPage } from "./pages/CouponsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { EventFormPage } from "./pages/EventFormPage";
import { ImportPage } from "./pages/ImportPage";
import { LoginPage } from "./pages/LoginPage";
import { OrdersPage } from "./pages/OrdersPage";
import { ScanPage } from "./pages/ScanPage";

function App() {
  return (
    <>
      <Header />
      <main className="main">
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route
            path="/"
            element={
              <RequireAdmin>
                <DashboardPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/events/new"
            element={
              <RequireAdmin>
                <EventFormPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/events/:eventId/edit"
            element={
              <RequireAdmin>
                <EventFormPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/coupons"
            element={
              <RequireAdmin>
                <CouponsPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/orders"
            element={
              <RequireAdmin>
                <OrdersPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/import"
            element={
              <RequireAdmin>
                <ImportPage />
              </RequireAdmin>
            }
          />
          <Route
            path="/scan"
            element={
              <RequireAdmin>
                <ScanPage />
              </RequireAdmin>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </>
  );
}

export default App;
