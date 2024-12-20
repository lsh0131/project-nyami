package com.project.controller;

import java.io.File;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.project.domain.Member;
import com.project.domain.Point;
import com.project.domain.Review;
import com.project.dto.CustomUserDetails;
import com.project.dto.ReviewWithNicknameDTO;
import com.project.service.PointService;
import com.project.service.ReviewService;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;
	private final PointService pointService;

	@GetMapping("/getReviews")
	@ResponseBody
	public List<ReviewWithNicknameDTO> getReviews(@RequestParam("store_id") long storeId) {

		System.out.println("Getting reviews for store ID: " + storeId);
		return reviewService.getReviewsByStoreId(storeId);
	}

	// ================================================================================================
	// => 1. 리뷰 직성 버튼을 누름
	// => 2. 내가 작성한 리뷰가 없을 경우 포인트 지급 및 리뷰 저장 및 목록 출력
	// => 3. 내가 작성한 리뷰가 있고, status가 active로 리뷰 목록에 보일 경우 중복 알람
	// => 4. 내가 작성한 리뷰가 있지만, 이미 삭제 버튼을 통해 status가 active가 아닌 hidden일 경우 이전 리뷰 삭제 후 재
	// 등록
	// => 4-1. 가장 중요한 포인트는 지급되지 않음
	// ==============================================================================================

	@PostMapping("/submitReview")
	public String submitReview(@RequestParam("storeId") long storeId, @RequestParam("score") double score,
			@RequestParam("content") String content,
			@RequestParam(value = "images", required = false) List<MultipartFile> images,
			@AuthenticationPrincipal CustomUserDetails userDetails, HttpSession session,
			RedirectAttributes redirectAttributes, Model model) {

		Member member = userDetails.getMember();
		long memberId = member.getId();

		Review existingReview = reviewService.findReviewByUserAndStore(memberId, storeId);
		boolean pointGiven = false; // 포인트 지급 여부를 저장할 변수
		long pointValue = 100L; // 기본 포인트 값
		String category = "일반리뷰"; // 기본 카테고리 값

		// 기존 리뷰 처리
		if (existingReview != null) {
			if ("active".equals(existingReview.getStatus())) {
				redirectAttributes.addFlashAttribute("duplicateReviewMessage", "이미 리뷰를 작성하셨습니다.");
				return "redirect:/storeDetail?store_ID=" + storeId;
			} else if ("hidden".equals(existingReview.getStatus())) {
				reviewService.deleteReview(existingReview.getId());
			}
		}

		if (images != null && !images.isEmpty()) {
			boolean allFilesEmpty = true;
			for (MultipartFile image : images) {
				if (!image.isEmpty()) {
					allFilesEmpty = false; // 파일이 비어 있지 않으면 200 포인트 지급
					break;
				}
			}

			if (allFilesEmpty) {
				// 이미지가 비어 있다면 100 포인트 지급
				pointValue = 100L;
				category = "일반리뷰";
			} else {
				// 실제 이미지가 있다면 200 포인트 지급
				pointValue = 200L;
				category = "사진리뷰";
			}
		} else {
			// 이미지가 아예 없으면 100 포인트 지급
			pointValue = 100L;
			category = "일반리뷰";
		}

		// 포인트 지급
		Point newPoint = Point.insertPoint(memberId, category, pointValue, "지급", "active");
		pointService.insertPoint(newPoint);
		pointGiven = true; // 포인트 지급됨

		// ReviewDomain 객체 생성 및 값 설정
		Review newReview = new Review();
		newReview.setMemberId(memberId);
		newReview.setStoreId(storeId);
		newReview.setScore(score);
		newReview.setContent(content);
		newReview.setCreatedAt(new Timestamp(System.currentTimeMillis())); // 현재 시간 설정

		System.out.println("images: 있나?" + images); // images 리스트 상태 확인

		if (pointGiven) {
			redirectAttributes.addFlashAttribute("pointMessage",
					pointValue == 200L ? "리뷰 작성 및 이미지 등록으로 200포인트가 지급되었습니다!" : "리뷰 작성으로 100포인트가 지급되었습니다!");
		}
		// 이미지가 존재할 경우에만 파일 처리 수행
		if (images != null && !images.isEmpty()) {
			StringBuilder imagePaths = new StringBuilder();
			String uploadDir = session.getServletContext().getRealPath("upload");

			// 업로드 디렉토리가 존재하지 않으면 생성
			new File(uploadDir).mkdirs();

			// images 리스트에서 각 파일 처리
			for (MultipartFile image : images) {
				if (!image.isEmpty()) { // 빈 파일 체크
					try {
						String safeFileName = System.currentTimeMillis() + "_"
								+ image.getOriginalFilename().replaceAll("[^a-zA-Z0-9.]", "_");
						String filePath = uploadDir + "/" + safeFileName;

						image.transferTo(new File(filePath)); // 파일 저장
						imagePaths.append(safeFileName).append(","); // 파일명 추가
					} catch (IOException e) {
						e.printStackTrace(); // 파일 저장 실패 시 오류 출력
					}
				}
			}

			// 이미지 경로 설정 (마지막 쉼표 제거)
			newReview.setReviewImage(imagePaths.length() > 0 ? imagePaths.substring(0, imagePaths.length() - 1) : null);
		} else {
			newReview.setReviewImage(null); // 이미지가 없을 경우 null 설정
		}

		// 리뷰 저장
		reviewService.submitReview(newReview);

		// 다시 원래 페이지로 리디렉션
		return "redirect:/storeDetail?store_ID=" + storeId;
	}

	// 리뷰 수정 요청 처리
	@PostMapping("/updateReview")
	public ResponseEntity<String> updateReview(@RequestBody Review review) {
		try {
			reviewService.updateReview(review);
			return ResponseEntity.ok("리뷰가 수정되었습니다.");
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("리뷰 수정에 실패했습니다.");
		}
	}

	// 리뷰 삭제 요청 처리
	// @PostMapping("/deleteReview")
	// public ResponseEntity<?> deleteReview(@RequestBody Map<String, Object>
	// reviewDetails) {
	// try {
	// reviewService.deleteReview(reviewDetails); // 서비스에서 삭제 메서드 호출
	// return ResponseEntity.ok().build(); // 성공적인 응답 반환
	// } catch (Exception e) {
	// return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("리뷰 삭제에
	// 실패했습니다.");
	// }
	// }

	@PostMapping("/hiddenReview")
	public ResponseEntity<?> hiddenReview(@RequestBody Map<String, Object> reviewDetails) {
		try {
			Long reviewId = Long.parseLong(reviewDetails.get("id").toString());
			reviewService.hiddenReview(reviewId); // 숨김 처리 메서드 호출
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("리뷰 삭제에 실패했습니다.");
		}
	}
}