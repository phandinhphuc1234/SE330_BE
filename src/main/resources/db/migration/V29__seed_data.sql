-- ============================================================
-- seed_data.sql
-- Dữ liệu mẫu cho ứng dụng Quản lý Thư viện
-- ============================================================

-- ============================================================
-- 1. AUTHORS (30 tác giả)
-- ============================================================
INSERT INTO authors (name, bio) VALUES
('Robert C. Martin',      'Kỹ sư phần mềm người Mỹ, tác giả của Clean Code và Clean Architecture'),
('Martin Fowler',         'Nhà tư vấn phần mềm người Anh, chuyên gia về thiết kế phần mềm'),
('Donald Knuth',          'Nhà khoa học máy tính người Mỹ, tác giả bộ sách The Art of Computer Programming'),
('Andrew Hunt',           'Lập trình viên và tác giả, đồng tác giả của The Pragmatic Programmer'),
('Erich Gamma',           'Nhà khoa học máy tính người Thụy Sĩ, đồng tác giả của Design Patterns'),
('Nam Cao',               'Nhà văn hiện thực Việt Nam, tác giả của Chí Phèo và Lão Hạc'),
('Nguyễn Du',             'Đại thi hào dân tộc Việt Nam, tác giả Truyện Kiều'),
('Tô Hoài',               'Nhà văn Việt Nam nổi tiếng với Dế Mèn Phiêu Lưu Ký'),
('Ngô Tất Tố',            'Nhà văn và nhà báo Việt Nam, tác giả của Tắt Đèn'),
('Franz Kafka',           'Nhà văn người Séc viết bằng tiếng Đức, tác giả của Hóa thân'),
('Albert Camus',          'Triết gia và nhà văn người Pháp, đoạt giải Nobel Văn học 1957'),
('George Orwell',         'Nhà văn và nhà báo người Anh, tác giả 1984 và Trại súc vật'),
('Ernest Hemingway',      'Nhà văn người Mỹ, đoạt giải Nobel Văn học 1954'),
('Gabriel García Márquez','Nhà văn người Colombia, tác giả Trăm Năm Cô Đơn'),
('Malcolm Gladwell',      'Nhà báo và tác giả người Canada, nổi tiếng với Outliers'),
('Robert Kiyosaki',       'Doanh nhân và tác giả người Mỹ, nổi tiếng với Rich Dad Poor Dad'),
('Stephen Hawking',       'Nhà vật lý lý thuyết người Anh, tác giả Lược Sử Thời Gian'),
('Richard Feynman',       'Nhà vật lý người Mỹ, đoạt giải Nobel Vật lý 1965'),
('Yuval Noah Harari',     'Nhà sử học người Israel, tác giả Sapiens và Homo Deus'),
('Nguyễn Nhật Ánh',       'Nhà văn Việt Nam nổi tiếng với nhiều tác phẩm dành cho tuổi trẻ'),
('Dale Carnegie',         'Nhà văn và diễn giả người Mỹ, tác giả Đắc Nhân Tâm'),
('Napoleon Hill',         'Tác giả người Mỹ, nổi tiếng với Think and Grow Rich'),
('Plato',                 'Triết gia Hy Lạp cổ đại, học trò của Socrates'),
('Tôn Tử',                'Nhà quân sự và triết gia Trung Hoa cổ đại, tác giả Binh Pháp Tôn Tử')
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- 2. BOOKS (dùng subquery tìm category_id theo tên)
-- ============================================================
INSERT INTO books (title, isbn, total_copies, available_copies, published_date, language, edition, category_id) VALUES
-- Công nghệ thông tin
('Clean Code',                          '978-0132350884', 4, 4, '2008-08-01', 'en', '1st Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('The Pragmatic Programmer',            '978-0135957059', 3, 3, '2019-09-13', 'en', '2nd Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Design Patterns',                     '978-0201633610', 3, 3, '1994-10-31', 'en', '1st Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Refactoring',                         '978-0134757599', 3, 3, '2018-11-20', 'en', '2nd Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Clean Architecture',                  '978-0134494166', 3, 3, '2017-09-20', 'en', '1st Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('The Art of Computer Programming',     '978-0201896831', 2, 2, '1997-07-04', 'en', '3rd Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Introduction to Algorithms',          '978-0262046305', 4, 4, '2022-04-05', 'en', '4th Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Học lập trình Java cơ bản',           '978-6042174289', 5, 5, '2020-01-15', 'vi', '1st Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Lập trình Python cho người mới',      '978-6042198765', 5, 5, '2021-06-10', 'vi', '2nd Edition',  (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
('Trí tuệ nhân tạo: Cơ bản và ứng dụng','978-6042112233', 3, 3, '2022-03-20', 'vi', '1st Edition', (SELECT id FROM categories WHERE name='Công nghệ thông tin')),
-- Văn học Việt Nam
('Chí Phèo',                            '978-6049562019', 4, 4, '1941-01-01', 'vi', 'Tái bản 2020', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Lão Hạc',                             '978-6049562026', 4, 4, '1943-01-01', 'vi', 'Tái bản 2020', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Truyện Kiều',                         '978-6042112345', 5, 5, '1820-01-01', 'vi', 'Tái bản 2021', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Dế Mèn Phiêu Lưu Ký',                '978-6049562033', 5, 5, '1941-01-01', 'vi', 'Tái bản 2022', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Tắt Đèn',                             '978-6049562040', 3, 3, '1937-01-01', 'vi', 'Tái bản 2019', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Mắt Biếc',                            '978-6042223344', 5, 5, '1990-01-01', 'vi', 'Tái bản 2023', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
('Tôi Thấy Hoa Vàng Trên Cỏ Xanh',     '978-6042334455', 4, 4, '1994-01-01', 'vi', 'Tái bản 2022', (SELECT id FROM categories WHERE name='Văn học Việt Nam')),
-- Văn học nước ngoài
('Hóa Thân',                            '978-6049123456', 3, 3, '1915-01-01', 'vi', 'Dịch 2018',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
('Người Xa Lạ',                         '978-6049234567', 3, 3, '1942-01-01', 'vi', 'Dịch 2019',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
('1984',                                '978-6049345678', 4, 4, '1949-06-08', 'vi', 'Dịch 2020',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
('Trại Súc Vật',                        '978-6049456789', 4, 4, '1945-08-17', 'vi', 'Dịch 2020',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
('Ông Già Và Biển Cả',                  '978-6049567890', 3, 3, '1952-09-01', 'vi', 'Dịch 2018',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
('Trăm Năm Cô Đơn',                     '978-6049678901', 3, 3, '1967-05-30', 'vi', 'Dịch 2021',    (SELECT id FROM categories WHERE name='Văn học nước ngoài')),
-- Kinh tế & Kinh doanh
('Cha Giàu Cha Nghèo',                  '978-6042445566', 5, 5, '1997-04-01', 'vi', 'Tái bản 2022', (SELECT id FROM categories WHERE name='Kinh tế & Kinh doanh')),
('Outliers: Câu Chuyện Về Thành Công',  '978-6042778899', 4, 4, '2008-11-18', 'vi', 'Dịch 2021',    (SELECT id FROM categories WHERE name='Kinh tế & Kinh doanh')),
('Tinh Thần Khởi Nghiệp',              '978-6042889900', 3, 3, '2011-09-13', 'vi', 'Dịch 2020',    (SELECT id FROM categories WHERE name='Kinh tế & Kinh doanh')),
-- Khoa học tự nhiên
('Lược Sử Thời Gian',                   '978-6042990011', 4, 4, '1988-04-01', 'vi', 'Dịch 2020',    (SELECT id FROM categories WHERE name='Khoa học tự nhiên')),
('Vũ Trụ Trong Vỏ Hạt Dẻ',             '978-6042001122', 3, 3, '2001-11-06', 'vi', 'Dịch 2019',    (SELECT id FROM categories WHERE name='Khoa học tự nhiên')),
('Surely You''re Joking Mr. Feynman',   '978-0393316049', 3, 3, '1985-01-01', 'en', '1st Edition',  (SELECT id FROM categories WHERE name='Khoa học tự nhiên')),
-- Lịch sử & Địa lý
('Sapiens: Lược Sử Loài Người',         '978-6042112211', 5, 5, '2011-01-01', 'vi', 'Dịch 2022',    (SELECT id FROM categories WHERE name='Lịch sử & Địa lý')),
('Homo Deus: Lược Sử Tương Lai',        '978-6042223322', 4, 4, '2015-01-01', 'vi', 'Dịch 2021',    (SELECT id FROM categories WHERE name='Lịch sử & Địa lý')),
('Lịch Sử Việt Nam',                    '978-6042334433', 4, 4, '2017-01-01', 'vi', '3rd Edition',  (SELECT id FROM categories WHERE name='Lịch sử & Địa lý')),
-- Tâm lý & Kỹ năng sống
('Đắc Nhân Tâm',                        '978-6042556655', 6, 6, '1936-10-01', 'vi', 'Tái bản 2023', (SELECT id FROM categories WHERE name='Tâm lý & Kỹ năng sống')),
('Nghĩ Giàu Làm Giàu',                  '978-6042667766', 5, 5, '1937-03-01', 'vi', 'Tái bản 2022', (SELECT id FROM categories WHERE name='Tâm lý & Kỹ năng sống')),
('Sức Mạnh Của Thói Quen',              '978-6042778877', 4, 4, '2012-02-28', 'vi', 'Dịch 2021',    (SELECT id FROM categories WHERE name='Tâm lý & Kỹ năng sống')),
-- Thiếu nhi
('Hoàng Tử Bé',                         '978-6042990099', 6, 6, '1943-04-06', 'vi', 'Tái bản 2023', (SELECT id FROM categories WHERE name='Thiếu nhi')),
('Kính Vạn Hoa',                        '978-6042112200', 5, 5, '1995-01-01', 'vi', 'Tái bản 2022', (SELECT id FROM categories WHERE name='Thiếu nhi')),
('Cho Tôi Xin Một Vé Đi Tuổi Thơ',     '978-6042223300', 5, 5, '2008-01-01', 'vi', 'Tái bản 2023', (SELECT id FROM categories WHERE name='Thiếu nhi')),
-- Triết học & Tôn giáo
('Binh Pháp Tôn Tử',                    '978-6042334400', 4, 4, '0500-01-01', 'vi', 'Dịch 2020',    (SELECT id FROM categories WHERE name='Triết học & Tôn giáo')),
('Cộng Hòa (The Republic)',             '978-6042445500', 3, 3, '0380-01-01', 'vi', 'Dịch 2019',    (SELECT id FROM categories WHERE name='Triết học & Tôn giáo')),
-- Y học & Sức khỏe
('Giải Phẫu Học Người',                 '978-6042667700', 3, 3, '2019-01-01', 'vi', '5th Edition',  (SELECT id FROM categories WHERE name='Y học & Sức khỏe')),
('Dinh Dưỡng Và Sức Khỏe',             '978-6042778800', 4, 4, '2020-06-01', 'vi', '2nd Edition',  (SELECT id FROM categories WHERE name='Y học & Sức khỏe')),
('Tâm Lý Học Về Giấc Ngủ',             '978-6042889988', 3, 3, '2021-03-15', 'vi', '1st Edition',  (SELECT id FROM categories WHERE name='Y học & Sức khỏe'))
ON CONFLICT (isbn) DO NOTHING;

-- ============================================================
-- 3. BOOK_AUTHORS
-- ============================================================
INSERT INTO book_authors (book_id, author_id)
SELECT b.id, a.id FROM books b, authors a WHERE
    (b.isbn = '978-0132350884' AND a.name = 'Robert C. Martin') OR
    (b.isbn = '978-0134494166' AND a.name = 'Robert C. Martin') OR
    (b.isbn = '978-0135957059' AND a.name = 'Andrew Hunt') OR
    (b.isbn = '978-0201633610' AND a.name = 'Erich Gamma') OR
    (b.isbn = '978-0134757599' AND a.name = 'Martin Fowler') OR
    (b.isbn = '978-0201896831' AND a.name = 'Donald Knuth') OR
    (b.isbn = '978-6049562019' AND a.name = 'Nam Cao') OR
    (b.isbn = '978-6049562026' AND a.name = 'Nam Cao') OR
    (b.isbn = '978-6042112345' AND a.name = 'Nguyễn Du') OR
    (b.isbn = '978-6049562033' AND a.name = 'Tô Hoài') OR
    (b.isbn = '978-6049562040' AND a.name = 'Ngô Tất Tố') OR
    (b.isbn = '978-6042223344' AND a.name = 'Nguyễn Nhật Ánh') OR
    (b.isbn = '978-6042334455' AND a.name = 'Nguyễn Nhật Ánh') OR
    (b.isbn = '978-6042990099' AND a.name = 'Nguyễn Nhật Ánh') OR
    (b.isbn = '978-6042112200' AND a.name = 'Nguyễn Nhật Ánh') OR
    (b.isbn = '978-6042223300' AND a.name = 'Nguyễn Nhật Ánh') OR
    (b.isbn = '978-6049123456' AND a.name = 'Franz Kafka') OR
    (b.isbn = '978-6049234567' AND a.name = 'Albert Camus') OR
    (b.isbn = '978-6049345678' AND a.name = 'George Orwell') OR
    (b.isbn = '978-6049456789' AND a.name = 'George Orwell') OR
    (b.isbn = '978-6049567890' AND a.name = 'Ernest Hemingway') OR
    (b.isbn = '978-6049678901' AND a.name = 'Gabriel García Márquez') OR
    (b.isbn = '978-6042445566' AND a.name = 'Robert Kiyosaki') OR
    (b.isbn = '978-6042778899' AND a.name = 'Malcolm Gladwell') OR
    (b.isbn = '978-6042990011' AND a.name = 'Stephen Hawking') OR
    (b.isbn = '978-6042001122' AND a.name = 'Stephen Hawking') OR
    (b.isbn = '978-0393316049' AND a.name = 'Richard Feynman') OR
    (b.isbn = '978-6042112211' AND a.name = 'Yuval Noah Harari') OR
    (b.isbn = '978-6042223322' AND a.name = 'Yuval Noah Harari') OR
    (b.isbn = '978-6042556655' AND a.name = 'Dale Carnegie') OR
    (b.isbn = '978-6042667766' AND a.name = 'Napoleon Hill') OR
    (b.isbn = '978-6042334400' AND a.name = 'Tôn Tử') OR
    (b.isbn = '978-6042445500' AND a.name = 'Plato')
ON CONFLICT DO NOTHING;

-- ============================================================
-- 3.5. EBOOK URLs
-- Gán ebook_url cho một số sách để test tính năng đọc online
-- ============================================================
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/1342/1342-pdf.pdf' WHERE isbn = '978-6049345678'; -- 1984
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/5200/5200-pdf.pdf' WHERE isbn = '978-6049123456'; -- Hóa Thân
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/84/84-pdf.pdf'     WHERE isbn = '978-6049456789'; -- Trại Súc Vật
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/1080/1080-pdf.pdf' WHERE isbn = '978-6049234567'; -- Người Xa Lạ
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/2554/2554-pdf.pdf' WHERE isbn = '978-6042112345'; -- Truyện Kiều
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/1727/1727-pdf.pdf' WHERE isbn = '978-6042445500'; -- Cộng Hòa
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/205/205-pdf.pdf'   WHERE isbn = '978-6042990099'; -- Hoàng Tử Bé
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/514/514-pdf.pdf'   WHERE isbn = '978-6042556655'; -- Đắc Nhân Tâm
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/4300/4300-pdf.pdf' WHERE isbn = '978-0132350884'; -- Clean Code
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/2600/2600-pdf.pdf' WHERE isbn = '978-6042112211'; -- Sapiens
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/2701/2701-pdf.pdf' WHERE isbn = '978-6042445566'; -- Cha Giàu Cha Nghèo
UPDATE books SET ebook_url = 'https://www.gutenberg.org/files/98/98-pdf.pdf'     WHERE isbn = '978-6042334400'; -- Binh Pháp Tôn Tử

-- ============================================================
-- 4. BOOK_COPIES
-- ============================================================
INSERT INTO book_copies (book_id, barcode, status, condition, location)
SELECT
    b.id,
    CONCAT('BC-', LPAD(b.id::text, 4, '0'), '-', LPAD(n::text, 3, '0')),
    'AVAILABLE',
    CASE (n % 3)
        WHEN 0 THEN 'GOOD'
        WHEN 1 THEN 'EXCELLENT'
        ELSE 'FAIR'
    END,
    CONCAT('Kệ ', CHR((64 + ((b.id % 8) + 1))::int), '-', LPAD(((n * b.id) % 20 + 1)::text, 2, '0'))
FROM books b
CROSS JOIN generate_series(1, b.total_copies) AS n
ON CONFLICT (barcode) DO NOTHING;

-- ============================================================
-- Kiểm tra kết quả
-- ============================================================
SELECT 'Categories' as type, COUNT(*) as total FROM categories
UNION ALL SELECT 'Authors',     COUNT(*) FROM authors
UNION ALL SELECT 'Books',       COUNT(*) FROM books
UNION ALL SELECT 'Book Copies', COUNT(*) FROM book_copies;
