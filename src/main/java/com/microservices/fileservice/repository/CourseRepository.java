package com.microservices.fileservice.repository;

import com.microservices.fileservice.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByInstructorId(String instructorId);
    List<Course> findByStatus(Course.CourseStatus status);
    List<Course> findByInstructorIdAndStatus(String instructorId, Course.CourseStatus status);
    List<Course> findByEnrolledStudentsContaining(String studentId);
}


