import { BrowserRouter, Routes, Route } from 'react-router-dom';
import StacksList from '@/pages/StacksList';
import StackDetail from '@/pages/StackDetail';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<StacksList />} />
        <Route path="/stacks/:name" element={<StackDetail />} />
      </Routes>
    </BrowserRouter>
  );
}
