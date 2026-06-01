package com.vn.controller.staff;

import com.vn.controller.StaffDashboardController;
import com.vn.controller.StaffHoldController;
import com.vn.controller.StaffLoanController;
import com.vn.controller.StaffMemberController;
import com.vn.dto.staff.dashboard.response.StaffDashboardSummaryResponse;
import com.vn.dto.staff.hold.response.StaffHoldResponse;
import com.vn.dto.staff.loan.response.StaffLoanResponse;
import com.vn.dto.staff.member.response.StaffMemberDetailResponse;
import com.vn.dto.staff.member.response.StaffMemberListItemResponse;
import com.vn.exception.GlobalExceptionHandler;
import com.vn.service.StaffDashboardService;
import com.vn.service.StaffHoldService;
import com.vn.service.StaffLoanService;
import com.vn.service.StaffMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class StaffApiControllerTest {

    @Mock
    private StaffMemberService staffMemberService;

    @Mock
    private StaffLoanService staffLoanService;

    @Mock
    private StaffHoldService staffHoldService;

    @Mock
    private StaffDashboardService staffDashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new StaffMemberController(staffMemberService),
                        new StaffLoanController(staffLoanService),
                        new StaffHoldController(staffHoldService),
                        new StaffDashboardController(staffDashboardService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @Test
    void searchStaffMembers_shouldReturnMembersAndPageMeta() throws Exception {
        PageRequest pageable = PageRequest.of(1, 5);
        when(staffMemberService.searchMembers("nguyen", "ACTIVE", true, 1, 5))
                .thenReturn(new PageImpl<>(List.of(memberListItem()), pageable, 12));

        mockMvc.perform(get("/api/staff/members")
                        .param("q", "nguyen")
                        .param("status", "ACTIVE")
                        .param("hasOverdue", "true")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy danh sách bạn đọc thành công"))
                .andExpect(jsonPath("$.data[0].memberId").value(2))
                .andExpect(jsonPath("$.data[0].fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data[0].activeLoansCount").value(3))
                .andExpect(jsonPath("$.data[0].overdueLoansCount").value(1))
                .andExpect(jsonPath("$.data[0].unpaidFineTotal").value(15000))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(5))
                .andExpect(jsonPath("$.meta.totalElements").value(12))
                .andExpect(jsonPath("$.meta.totalPages").value(3));

        verify(staffMemberService).searchMembers("nguyen", "ACTIVE", true, 1, 5);
    }

    @Test
    void getStaffMemberDetail_shouldReturnMemberProfileSummary() throws Exception {
        when(staffMemberService.getMemberDetail(2L)).thenReturn(memberDetail());

        mockMvc.perform(get("/api/staff/members/{memberId}", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy hồ sơ bạn đọc thành công"))
                .andExpect(jsonPath("$.data.memberId").value(2))
                .andExpect(jsonPath("$.data.fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data.openLoansCount").value(4))
                .andExpect(jsonPath("$.data.borrowHistoryCount").value(10))
                .andExpect(jsonPath("$.data.activeHoldsCount").value(2));

        verify(staffMemberService).getMemberDetail(2L);
    }

    @Test
    void getStaffMemberLoans_shouldReturnMemberLoansAndPageMeta() throws Exception {
        PageRequest pageable = PageRequest.of(0, 10);
        when(staffMemberService.getMemberLoans(2L, "BORROWED", true, false, 0, 10))
                .thenReturn(new PageImpl<>(List.of(staffLoan()), pageable, 1));

        mockMvc.perform(get("/api/staff/members/{memberId}/loans", 2L)
                        .param("status", "BORROWED")
                        .param("openOnly", "true")
                        .param("overdue", "false")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy danh sách lượt mượn của bạn đọc thành công"))
                .andExpect(jsonPath("$.data[0].borrowId").value(100))
                .andExpect(jsonPath("$.data[0].memberId").value(2))
                .andExpect(jsonPath("$.data[0].bookTitle").value("Clean Code"))
                .andExpect(jsonPath("$.data[0].itemBarcode").value("LIB-2026-000001"))
                .andExpect(jsonPath("$.data[0].overdue").value(false))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        verify(staffMemberService).getMemberLoans(2L, "BORROWED", true, false, 0, 10);
    }

    @Test
    void searchStaffLoans_shouldReturnLoansAndPageMeta() throws Exception {
        PageRequest pageable = PageRequest.of(2, 10);
        when(staffLoanService.searchLoans("clean", "BORROWED", true, false, "2026-05-01", "2026-06-01", 2, 10))
                .thenReturn(new PageImpl<>(List.of(staffLoan()), pageable, 21));

        mockMvc.perform(get("/api/staff/loans")
                        .param("q", "clean")
                        .param("status", "BORROWED")
                        .param("openOnly", "true")
                        .param("overdue", "false")
                        .param("dueFrom", "2026-05-01")
                        .param("dueTo", "2026-06-01")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy danh sách lượt mượn thành công"))
                .andExpect(jsonPath("$.data[0].borrowId").value(100))
                .andExpect(jsonPath("$.data[0].memberName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data[0].bookTitle").value("Clean Code"))
                .andExpect(jsonPath("$.data[0].fineStatus").value("UNPAID"))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.totalElements").value(21))
                .andExpect(jsonPath("$.meta.totalPages").value(3));

        verify(staffLoanService).searchLoans("clean", "BORROWED", true, false, "2026-05-01", "2026-06-01", 2, 10);
    }

    @Test
    void searchStaffHolds_shouldReturnHoldsAndPageMeta() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        when(staffHoldService.searchHolds("READY_FOR_PICKUP", 0, 20))
                .thenReturn(new PageImpl<>(List.of(staffHold()), pageable, 1));

        mockMvc.perform(get("/api/staff/holds")
                        .param("status", "READY_FOR_PICKUP")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy danh sách giữ chỗ toàn hệ thống thành công"))
                .andExpect(jsonPath("$.data[0].holdId").value(700))
                .andExpect(jsonPath("$.data[0].memberName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data[0].bookTitle").value("Clean Code"))
                .andExpect(jsonPath("$.data[0].status").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.data[0].assignedCopyBarcode").value("LIB-2026-000001"))
                .andExpect(jsonPath("$.meta.totalElements").value(1));

        verify(staffHoldService).searchHolds("READY_FOR_PICKUP", 0, 20);
    }

    @Test
    void getStaffDashboardSummary_shouldReturnSummaryCounters() throws Exception {
        when(staffDashboardService.getSummary()).thenReturn(dashboardSummary());

        mockMvc.perform(get("/api/staff/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Lấy tổng quan dashboard staff thành công"))
                .andExpect(jsonPath("$.data.activeLoans").value(12))
                .andExpect(jsonPath("$.data.overdueLoans").value(3))
                .andExpect(jsonPath("$.data.holdsReadyForPickup").value(4))
                .andExpect(jsonPath("$.data.unpaidFineCount").value(5))
                .andExpect(jsonPath("$.data.unpaidFineTotal").value(25000))
                .andExpect(jsonPath("$.data.borrowedToday").value(6))
                .andExpect(jsonPath("$.data.returnedToday").value(7));

        verify(staffDashboardService).getSummary();
    }

    private StaffMemberListItemResponse memberListItem() {
        return new StaffMemberListItemResponse(
                2L,
                "Nguyen Van A",
                "nguyenvana@example.com",
                "0900000001",
                "MEMBER",
                "ACTIVE",
                5,
                Instant.parse("2027-01-01T00:00:00Z"),
                3,
                1,
                2,
                new BigDecimal("15000"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private StaffMemberDetailResponse memberDetail() {
        return new StaffMemberDetailResponse(
                2L,
                "Nguyen Van A",
                "nguyenvana@example.com",
                "0900000001",
                "MEMBER",
                "ACTIVE",
                5,
                Instant.parse("2027-01-01T00:00:00Z"),
                3,
                4,
                1,
                10,
                2,
                new BigDecimal("15000"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z")
        );
    }

    private StaffLoanResponse staffLoan() {
        return new StaffLoanResponse(
                100L,
                2L,
                "Nguyen Van A",
                "nguyenvana@example.com",
                30L,
                "Clean Code",
                300L,
                "LIB-2026-000001",
                "BORROWED",
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-15T10:00:00Z"),
                null,
                "BORROWED",
                0,
                1,
                new BigDecimal("15000"),
                "UNPAID",
                false,
                0
        );
    }

    private StaffHoldResponse staffHold() {
        return new StaffHoldResponse(
                700L,
                2L,
                "Nguyen Van A",
                "nguyenvana@example.com",
                30L,
                "Clean Code",
                "READY_FOR_PICKUP",
                1,
                300L,
                "LIB-2026-000001",
                Instant.parse("2026-05-01T10:00:00Z"),
                Instant.parse("2026-05-02T10:00:00Z"),
                Instant.parse("2026-05-05T10:00:00Z")
        );
    }

    private StaffDashboardSummaryResponse dashboardSummary() {
        return new StaffDashboardSummaryResponse(
                12,
                3,
                4,
                5,
                new BigDecimal("25000"),
                6,
                7,
                Instant.parse("2026-05-30T10:00:00Z")
        );
    }
}
